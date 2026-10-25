// Campus Android adapter for LocalSend's Apache-2.0 Rust core.
// Upstream attribution and fixed revision: ../NOTICE.md.
use anyhow::{bail, Context, Result};
use futures_util::StreamExt;
use jni::{objects::{JObject, JString}, sys::jstring, JNIEnv};
use localsend::{crypto::cert, http::{client::LsHttpClientV2, dto_v2::{RegisterDtoV2, PrepareUploadRequestDtoV2},
    server::{self, ServerConfigV2, TlsConfig, v2::{ServerEventV2, PrepareUploadDecisionV2}, common::save::FileUploadTarget}, state::ClientInfo},
    model::{discovery::{DeviceType, ProtocolType}, transfer::{FileDto, FileContent}}, multicast::{self, MulticastConfig, MulticastDevice, MulticastEvent}, util::interface::InterfaceFilter};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{collections::{HashMap, VecDeque, HashSet}, path::PathBuf, sync::{Arc, Mutex, OnceLock}, time::{Duration, Instant}};
use std::os::fd::{FromRawFd, IntoRawFd, OwnedFd};
use tokio::{runtime::Runtime, sync::{mpsc, oneshot}};
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

#[derive(Clone, Serialize, Deserialize)]
struct Identity { private_key: String, certificate: String, fingerprint: String }
struct Pending { decision: oneshot::Sender<PrepareUploadDecisionV2>, files: HashMap<String, FileDto> }
struct Sending { token: CancellationToken, ip: String, port: u16, session: Option<String> }

/// 发现相关的前台状态。
///
/// 上游 `multicast::announce()` 一次只发 3 个数据报（100/500/2000ms），那是**丢包重试**，
/// 不是持续发现：对方晚几秒才进页面、或刚切换 Wi-Fi，我们就永远等不到 —— 这是「发现慢」的主因。
/// 所以在本适配层补一层前台调度 + 去重 + 退避，**不改 vendored 上游核心**。
#[derive(Default)]
struct Discovery {
    /// 每次重新开始发现就 +1；旧调度的任务看到轮次变了就自己退出，不会叠加广播
    round: u64,
    /// 本轮发现是从什么时候开始的（用来报「首次发现耗时」）
    started_at: Option<Instant>,
    /// 已经握手成功的设备：fingerprint -> (ip, 成功时刻)。30 秒内重复组播直接跳过。
    known: HashMap<String, (String, Instant)>,
    /// 正在握手中的 key，避免同一台设备把 8 个并发槽位占满
    inflight: HashSet<String>,
    /// 失败退避：key -> (下次最早可重试时刻, 连续失败次数)
    retry: HashMap<String, (Instant, u32)>,
    /// 组播被系统回收后自动重启的次数（上限 MAX_MULTICAST_RESTARTS）
    restarts: u32,
}
#[derive(Default)]
struct State {
    events: VecDeque<Value>, identity: Option<Identity>, alias: String, port: u16, root: PathBuf,
    server: Option<Arc<server::ServerHandle>>, multicast: Option<Arc<multicast::MulticastHandle>>,
    server_stop: Option<oneshot::Sender<()>>, multicast_stop: Option<oneshot::Sender<()>>,
    pending: HashMap<String, Pending>, accepted: HashMap<String, HashMap<String, FileDto>>,
    sends: HashMap<String, Sending>, generation: u64, owner: String,
    discovery: Discovery,
}
fn shared() -> &'static Mutex<State> { static STATE: OnceLock<Mutex<State>> = OnceLock::new(); STATE.get_or_init(|| Mutex::new(State::default())) }
fn runtime() -> &'static Runtime { static RT: OnceLock<Runtime> = OnceLock::new(); RT.get_or_init(|| tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().expect("runtime")) }
fn event(value: Value) {
    let mut s = shared().lock().unwrap();
    if value["type"] == "progress" { s.events.retain(|e| !(e["type"] == "progress" && e["id"] == value["id"])); }
    if s.events.len() >= 256 { s.events.pop_front(); }
    s.events.push_back(value);
}
fn identity() -> Result<Identity> { shared().lock().unwrap().identity.clone().context("Open the transfer page first") }
fn registration() -> Result<RegisterDtoV2> {
    let s = shared().lock().unwrap(); let identity = s.identity.as_ref().context("Not started")?;
    Ok(RegisterDtoV2 { alias: s.alias.clone(), version: "2.2".into(), device_model: Some("Campus Android".into()),
        device_type: Some(DeviceType::Mobile), fingerprint: identity.fingerprint.clone(), port: s.port, protocol: ProtocolType::Https, download: false })
}
fn client(pin: Option<String>, timeout: u64) -> Result<LsHttpClientV2> {
    let i = identity()?; Ok(LsHttpClientV2::try_new(&i.private_key, &i.certificate, pin, Some(Duration::from_secs(timeout)))?)
}
fn safe_ip(raw: &str) -> Result<String> {
    let ip: std::net::IpAddr = raw.parse().context("请输入有效的局域网 IPv4 地址")?;
    match ip { std::net::IpAddr::V4(v) if v.is_private() || v.is_link_local() || v.is_loopback() => Ok(raw.to_owned()), _ => bail!("This adapter only connects to local IPv4 networks") }
}
/// 一次「向对方注册」的握手。
///
/// `force` = 用户手动输入 IP 的连接：跳过 30 秒成功缓存与失败退避（用户明确点了就要试），
/// 但仍然走**在途去重**。组播发现则全程走缓存 + 退避，否则重复广播会把 8 个并发槽占满。
async fn discover(ip: String, port: u16, fingerprint: Option<String>, force: bool) -> Result<()> {
    let ip = safe_ip(&ip)?;
    let own = identity()?;
    if fingerprint.as_ref() == Some(&own.fingerprint) { return Ok(()) }
    let key = fingerprint.clone().unwrap_or_else(|| format!("{ip}:{port}"));
    {
        let mut s = shared().lock().unwrap();
        if !force {
            // 刚握过手的设备 30 秒内不再重复握手：重复组播非常常见，每次都走一遍 TLS 只会拖慢别的设备
            if s.discovery.known.get(&key).is_some_and(|(_, at)| at.elapsed() < Duration::from_secs(30)) { return Ok(()) }
            // 失败退避（1/2/4/8/16 秒封顶 20 秒），避免一台不在线的设备持续占坑
            if s.discovery.retry.get(&key).is_some_and(|(until, _)| *until > Instant::now()) { return Ok(()) }
        }
        if !s.discovery.inflight.insert(key.clone()) { return Ok(()) }
    }
    event(json!({"type":"probing","ip":ip,"port":port,"fingerprint":key}));

    enum Handshake { Own, Peer { fingerprint: String, alias: String } }
    let outcome: Result<Handshake> = async {
        let result = client(fingerprint, 5)?.register(ProtocolType::Https, &ip, port, registration()?).await?;
        let fp = result.cert_fingerprint.context("对方没有证明自己的 TLS 身份")?;
        Ok(if fp == own.fingerprint { Handshake::Own } else { Handshake::Peer { fingerprint: fp, alias: result.body.alias } })
    }.await;
    // 先解锁再发事件：event() 自己也要拿这把锁，持有不放会死锁
    let elapsed_ms = {
        let mut s = shared().lock().unwrap();
        s.discovery.inflight.remove(&key);
        s.discovery.started_at.map(|t| t.elapsed().as_millis() as u64).unwrap_or(0)
    };
    match outcome {
        Ok(Handshake::Own) => Ok(()),
        Ok(Handshake::Peer { fingerprint, alias }) => {
            {
                let mut s = shared().lock().unwrap();
                s.discovery.retry.remove(&key);
                s.discovery.known.insert(key.clone(), (ip.clone(), Instant::now()));
            }
            event(json!({"type":"device","ip":ip,"port":port,"alias":alias,"fingerprint":fingerprint,"discoveredInMs":elapsed_ms}));
            if force { event(json!({"type":"probe","ip":ip,"ok":true})); }
            Ok(())
        }
        Err(error) => {
            let message = error.to_string();
            {
                let mut s = shared().lock().unwrap();
                let fails = {
                    let entry = s.discovery.retry.entry(key.clone()).or_insert((Instant::now(), 0));
                    entry.1 = entry.1.saturating_add(1);
                    entry.1
                };
                let wait = Duration::from_secs((1u64 << fails.min(4)).min(20));
                s.discovery.retry.insert(key.clone(), (Instant::now() + wait, fails));
            }
            if force { event(json!({"type":"probe","ip":ip,"ok":false,"message":message})); }
            else { event(json!({"type":"probeFailed","ip":key,"message":message})); }
            Ok(())
        }
    }
}

/// 前台发现调度：在一段时间内反复广播。
///
/// 为什么必须自己做：上游 `announce()` 只发 3 个数据报（100/500/2000ms），是**丢包重试**。
/// 对方晚几秒才打开页面、或刚切到同一 Wi-Fi，单次广播就永远等不到，用户看到的就是「发现很慢」。
///
/// 每一轮单独 spawn（不 await）：一次 burst 本身要 2.6 秒，await 会把后面的轮次全挤掉。
/// 重新开始发现 / 关页面都会让 `round` 或 `generation` 变化，旧调度立刻退出，不会叠加广播。
fn schedule_announce(generation: u64) -> u64 {
    const SCHEDULE_MS: [u64; 9] = [0, 800, 2000, 4500, 9000, 18000, 30000, 60000, 120000];
    let round = {
        let mut s = shared().lock().unwrap();
        s.discovery.round = s.discovery.round.wrapping_add(1);
        // 重新开始发现就把计时归零，界面上报的「首次发现耗时」才是这一轮的
        s.discovery.started_at = Some(Instant::now());
        s.discovery.round
    };
    tokio::spawn(async move {
        let started = Instant::now();
        event(json!({"type":"discovery","state":"searching","round":round}));
        let mut previous = 0u64;
        for (index, at) in SCHEDULE_MS.iter().enumerate() {
            if *at > previous { tokio::time::sleep(Duration::from_millis(*at - previous)).await; }
            previous = *at;
            let handle = {
                let s = shared().lock().unwrap();
                if s.generation != generation || s.discovery.round != round { None } else { s.multicast.clone() }
            };
            let Some(m) = handle else { break };
            tokio::spawn(async move { m.announce().await; });
            let (known, inflight) = {
                let s = shared().lock().unwrap();
                (s.discovery.known.len(), s.discovery.inflight.len())
            };
            event(json!({"type":"timing","stage":"announce","round":index,"ms":started.elapsed().as_millis() as u64,
                "known":known,"inflight":inflight}));
        }
        event(json!({"type":"discovery","state":"idle","round":round}));
    });
    round
}
/// 组播被系统回收后自动重启的次数上限（超过就提示用户重开页面，避免无限重启打转）。
const MAX_MULTICAST_RESTARTS: u32 = 5;

/// 把上游核心的 tracing 日志接到 Android logcat。
///
/// **为什么必须有**：没有订阅器时，上游全部 `tracing::info!/warn!/error!` 被静默丢弃 ——
/// 组播到底绑没绑上网卡、有没有收包、什么时候放弃，在真机上一条都看不到。
/// Android 会把进程的 stdout/stderr 转发到 logcat（tag `System.out` / `System.err`），
/// 所以默认的 fmt writer 就够了，不需要额外的 android_logger 依赖。
#[cfg(target_os = "android")]
mod logcat_writer {
    use std::ffi::CString;
    use std::io::{self, Write};

    // 直接调 liblog。**不能指望 stdout** —— 实测这台 Android 15 上应用进程的
    // stdout/stderr **不会**被转发到 logcat（System.out 一条都没有）。
    #[link(name = "log")]
    extern "C" {
        fn __android_log_write(prio: i32, tag: *const std::os::raw::c_char, text: *const std::os::raw::c_char) -> i32;
    }

    pub struct Writer;
    impl Write for Writer {
        fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
            let text = String::from_utf8_lossy(buf);
            let text = text.trim_end();
            if !text.is_empty() {
                if let (Ok(tag), Ok(msg)) = (CString::new("campus-localsend"), CString::new(text)) {
                    unsafe { __android_log_write(4 /* ANDROID_LOG_INFO */, tag.as_ptr(), msg.as_ptr()); }
                }
            }
            Ok(buf.len())
        }
        fn flush(&mut self) -> io::Result<()> { Ok(()) }
    }

    pub struct MakeWriter;
    impl<'a> tracing_subscriber::fmt::MakeWriter<'a> for MakeWriter {
        type Writer = Writer;
        fn make_writer(&'a self) -> Self::Writer { Writer }
    }
}

fn init_tracing() {
    static ONCE: std::sync::Once = std::sync::Once::new();
    ONCE.call_once(|| {
        #[cfg(target_os = "android")]
        let _ = tracing_subscriber::fmt()
            .with_writer(logcat_writer::MakeWriter)
            .with_max_level(tracing::Level::DEBUG)
            .with_ansi(false)
            .with_target(true)
            .try_init();
        #[cfg(not(target_os = "android"))]
        let _ = tracing_subscriber::fmt()
            .with_max_level(tracing::Level::DEBUG)
            .with_ansi(false)
            .with_target(true)
            .try_init();
        // 探针行：能在 adb logcat -s campus-localsend 里看到它，就说明订阅器真的生效了。
        tracing::info!("campus-localsend: tracing -> logcat (tag: campus-localsend)");
    });
}

/// 启动（或重启）组播发现：绑 socket、起事件循环、开始广播。
///
/// 单独抽出来是因为上游 `MulticastEvent::SocketsFailed` 明确要求**应用自己重启发现**
/// （vendor/core/src/multicast/mod.rs：应用被挂起时系统会回收 socket）。
/// 以前这里只处理 `Discovered`，socket 全挂之后组播就 **静默死亡且永不恢复**，
/// 连「重新发现」按钮都没用（`multicast.is_some()` 仍是 true）。
fn spawn_multicast(generation: u64, device: MulticastDevice) -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<()>> + Send>> {
    // 回调自己会调自己（SocketsFailed 重启）—— 直接写 async fn 会让 Send 的证明成环，
    // 所以这里直接返回一个 boxed future：递归只在运行时发生，类型不会无限展开。
    Box::pin(async move {
    let (mtx, mut mrx) = mpsc::channel(32);
    let (mstx, msrx) = oneshot::channel();
    let handle = Arc::new(multicast::start(MulticastConfig {
        group: multicast::DEFAULT_MULTICAST_GROUP,
        group_v6: None,
        port: multicast::DEFAULT_PORT,
        interface_filter: InterfaceFilter::default(),
        device: device.clone(),
        event_tx: mtx,
    }, msrx).await?);
    {
        let mut s = shared().lock().unwrap();
        if s.generation != generation { return Ok(()) }
        s.multicast_stop = Some(mstx);
        s.multicast = Some(handle);
    }
    let slots = Arc::new(tokio::sync::Semaphore::new(8));
    tokio::spawn(async move {
        while let Some(e) = mrx.recv().await {
            if shared().lock().unwrap().generation != generation { break }
            match e {
                MulticastEvent::Discovered { ip, message, .. } => {
                    if message.protocol == ProtocolType::Https {
                        // 能收到组播说明链路是好的，把自动重启计数清零
                        shared().lock().unwrap().discovery.restarts = 0;
                        if let Ok(permit) = slots.clone().try_acquire_owned() {
                            tokio::spawn(async move { let _permit = permit; let _ = discover(ip.to_string(), message.port, Some(message.fingerprint), false).await; });
                        }
                    }
                }
                MulticastEvent::SocketsFailed => {
                    let tries = { let mut s = shared().lock().unwrap(); s.discovery.restarts += 1; s.discovery.restarts };
                    if shared().lock().unwrap().generation != generation { break }
                    if tries > MAX_MULTICAST_RESTARTS {
                        event(json!({"type":"error","message":"局域网监听反复失效，请退出本页重新打开文件互传"}));
                        break;
                    }
                    let wait = Duration::from_secs(2 * tries.min(5) as u64);
                    event(json!({"type":"notice","message":format!("网络监听被系统中断，{} 秒后自动恢复发现…", wait.as_secs())}));
                    tracing::warn!("multicast sockets failed, restart #{} in {:?}", tries, wait);
                    tokio::time::sleep(wait).await;
                    let device = device.clone();
                    tokio::spawn(async move {
                        match spawn_multicast(generation, device).await {
                            Ok(()) => tracing::info!("multicast discovery restarted"),
                            Err(e) => event(json!({"type":"error","message":format!("自动恢复局域网发现失败：{e}")})),
                        }
                    });
                    break;
                }
            }
        }
    });
    schedule_announce(generation);
    Ok(())
    })
}

async fn start(args: Value) -> Result<Value> {
    { let mut s=shared().lock().unwrap(); if let Some(h)=s.server.clone() {
        s.owner=args["owner"].as_str().unwrap_or("").to_owned();
        return Ok(json!({"ok":true,"port":h.port(),"addresses":h.local_addresses().iter().map(ToString::to_string).collect::<Vec<_>>()}))
    } }
    let path = PathBuf::from(args["identityPath"].as_str().context("Identity path required")?);
    let root = PathBuf::from(args["receivePath"].as_str().context("Receive path required")?);
    std::fs::create_dir_all(&root)?;
    let i: Identity = match std::fs::read(&path).ok().and_then(|b| serde_json::from_slice(&b).ok()) {
        Some(i) => i,
        None => {
            let c = cert::generate_self_signed()?;
            let i = Identity { private_key: c.private_key_pem, certificate: c.certificate_pem, fingerprint: c.fingerprint };
            std::fs::write(&path, serde_json::to_vec(&i)?)?;
            #[cfg(unix)] { use std::os::unix::fs::PermissionsExt; std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600))?; }
            i
        }
    };
    let alias = args["alias"].as_str().unwrap_or("Campus Android").chars().take(40).collect::<String>();
    let (tx, mut rx) = mpsc::channel(32);
    let (stop_tx, stop_rx) = oneshot::channel();
    let info = ClientInfo { alias: alias.clone(), version:"2.2".into(), device_model:Some("Campus Android".into()), device_type:Some(DeviceType::Mobile), token:i.fingerprint.clone() };
    let handle = Arc::new(server::start_with_port(53317, Some(TlsConfig { cert:i.certificate.clone(), private_key:i.private_key.clone() }), info,
        None, Some(ServerConfigV2 { pin:None, verify_checksums:true, event_tx:tx }), None, stop_rx).await?);
    let generation = { let mut s=shared().lock().unwrap(); s.generation+=1; s.identity=Some(i.clone()); s.alias=alias.clone(); s.port=handle.port(); s.root=root;
        s.owner=args["owner"].as_str().unwrap_or("").to_owned();s.server_stop=Some(stop_tx); s.server=Some(handle.clone()); s.events.clear();
        // 新一次启动 = 新的端口与身份，之前记下的发现结果全部作废
        s.discovery = Discovery::default();
        s.generation };
    tokio::spawn(async move { while let Some(e)=rx.recv().await {
        if shared().lock().unwrap().generation != generation { break }
        match e {
            ServerEventV2::Register { ip, info } => {
                if info.protocol == ProtocolType::Https { event(json!({"type":"device","ip":ip.to_string(),"port":info.port,"alias":info.alias,"fingerprint":info.fingerprint})); }
            }
            ServerEventV2::PrepareUpload { session_id, info, files, decision_tx, cert_fingerprint, .. } => {
                let valid = cert_fingerprint.is_some() && files.len() <= 32 && !files.is_empty() && files.values().all(|f| f.size <= 2*1024*1024*1024) && files.values().map(|f| f.size).sum::<u64>() <= 4*1024*1024*1024;
                if !valid { let _=decision_tx.send(PrepareUploadDecisionV2::Decline); continue }
                let offered = serde_json::to_value(&files).unwrap_or(json!({}));
                shared().lock().unwrap().pending.insert(session_id.clone(), Pending { decision:decision_tx, files });
                event(json!({"type":"request","id":session_id,"alias":info.alias,"files":offered,"fingerprint":cert_fingerprint}));
                tokio::spawn(async move { tokio::time::sleep(Duration::from_secs(90)).await;
                    let pending=shared().lock().unwrap().pending.remove(&session_id);
                    if let Some(p)=pending { let _=p.decision.send(PrepareUploadDecisionV2::Decline); event(json!({"type":"requestExpired","id":session_id})); }
                });
            }
            ServerEventV2::FileUpload { session_id, file_id, file, target_tx } => {
                let root = { let s=shared().lock().unwrap();
                    if !s.accepted.get(&session_id).is_some_and(|m| m.contains_key(&file_id)) { continue }
                    s.root.clone()
                };
                let folder=root.join(Uuid::new_v4().to_string());
                if std::fs::create_dir(&folder).is_err() { continue }
                let filename=file.file_name.chars().map(|c| if c.is_control() || "/\\:*?\"<>|".contains(c) { '_' } else {c}).take(120).collect::<String>();
                let filename=if filename.is_empty() || filename=="." || filename==".." {"received-file".to_owned()} else {filename};
                let final_path=folder.join(filename); let partial=folder.join("upload.part");
                let (result_tx,result_rx)=oneshot::channel(); let (progress_tx,mut progress_rx)=mpsc::channel(4);
                let progress_id=format!("{session_id}:{file_id}"); let size=file.size;
                tokio::spawn(async move { while let Some(bytes)=progress_rx.recv().await { event(json!({"type":"progress","id":progress_id,"bytes":bytes,"size":size,"direction":"receive"})); } });
                let _=target_tx.send(FileUploadTarget::Path { path:partial.clone(), result_tx, progress_tx:Some(progress_tx) });
                tokio::spawn(async move {
                    match result_rx.await {
                        Ok(Ok(())) => { if std::fs::rename(&partial,&final_path).is_ok() { event(json!({"type":"received","id":session_id,"name":file.file_name,"path":final_path,"size":size})); } }
                        _ => { let _=std::fs::remove_file(&partial); event(json!({"type":"error","message":"接收中断，未保存未完成文件"})); }
                    }
                });
            }
            ServerEventV2::SessionEnd { session_id, reason } => { shared().lock().unwrap().accepted.remove(&session_id); event(json!({"type":"sessionEnd","id":session_id,"reason":format!("{reason:?}")})); }
            ServerEventV2::PrepareUploadAborted { session_id } => { shared().lock().unwrap().pending.remove(&session_id); event(json!({"type":"requestExpired","id":session_id})); }
            ServerEventV2::CancelReceived { ip, session_id } => { let s=shared().lock().unwrap(); for v in s.sends.values() { if v.ip==ip.to_string() && v.session.as_ref()==Some(&session_id) { v.token.cancel(); } } }
            ServerEventV2::ListenerFailed { .. } => event(json!({"type":"error","message":"局域网监听已停止，请重新打开文件互传"})),
        }
    }});
    let device = MulticastDevice { alias:alias.clone(),version:"2.2".into(),device_model:Some("Campus Android".into()),device_type:Some(DeviceType::Mobile),fingerprint:i.fingerprint.clone(),port:handle.port(),protocol:ProtocolType::Https,download:false };
    if spawn_multicast(generation, device).await.is_err() {
        event(json!({"type":"notice","message":"此网络不支持广播发现，可输入对方局域网 IP 连接"}));
    }
    Ok(json!({"ok":true,"port":handle.port(),"fingerprint":identity()?.fingerprint,"addresses":handle.local_addresses().iter().map(ToString::to_string).collect::<Vec<_>>()}))
}

async fn send(args:Value) -> Result<Value> {
    let ip=safe_ip(args["ip"].as_str().context("IP required")?)?;
    let port=u16::try_from(args["port"].as_u64().context("Port required")?)?;
    let fingerprint=args["fingerprint"].as_str().context("Discover the device first")?.to_owned();
    let raw=args["files"].as_array().context("Files required")?;
    if raw.is_empty() || raw.len()>32 { bail!("Choose 1 to 32 files") }
    let fds:Vec<i32>=raw.iter().map(|f| -> Result<i32> {let fd=i32::try_from(f["fd"].as_i64().context("File descriptor required")?)?;if fd<0 {bail!("Invalid file")};Ok(fd)}).collect::<Result<_>>()?;
    // Duplicate provider-owned Android descriptors. Kotlin always closes its originals.
    let owned:Vec<OwnedFd>=fds.into_iter().map(|fd| -> Result<OwnedFd> {let dup=unsafe{libc::dup(fd)};if dup<0 {return Err(std::io::Error::last_os_error().into())};Ok(unsafe{OwnedFd::from_raw_fd(dup)})}).collect::<Result<_>>()?;
    let mut contents=HashMap::new(); let mut files=HashMap::new();
    for (f,owned) in raw.iter().zip(owned) {
        let size=f["size"].as_u64().context("File size required")?;
        if size>2*1024*1024*1024 {bail!("File exceeds 2 GiB")}
        let id=Uuid::new_v4().to_string();
        files.insert(id.clone(),FileDto{id:id.clone(),file_name:f["name"].as_str().unwrap_or("file").chars().take(120).collect(),size,file_type:f["mime"].as_str().unwrap_or("application/octet-stream").into(),sha256:None,preview:None,metadata:None});
        contents.insert(id,owned);
    }
    let id=Uuid::new_v4().to_string();let token=CancellationToken::new();
    let client=client(Some(fingerprint),120)?; let info=registration()?;let result_id=id.clone();
    shared().lock().unwrap().sends.insert(id.clone(),Sending{token:token.clone(),ip:ip.clone(),port,session:None});
    tokio::spawn(async move {
        let outcome:Result<()> = async {
            event(json!({"type":"sending","id":id,"message":"等待对方确认接收"}));
            let response=client.prepare_upload(ProtocolType::Https,&ip,port,None,PrepareUploadRequestDtoV2{info,files:files.clone()},None,token.clone()).await?;
            let response=response.response.context("对方没有接受这些文件")?;
            if let Some(s)=shared().lock().unwrap().sends.get_mut(&id) {s.session=Some(response.session_id.clone())}
            for (file_id,upload_token) in &response.files {
                let Some(fd)=contents.remove(file_id) else {continue}; let meta=files.get(file_id).context("Unknown accepted file")?;
                let content=FileContent::Fd(fd.into_raw_fd());let progress_id=format!("{id}:{file_id}");let size=meta.size;
                let mut sent=0u64;let stream=tokio_stream::wrappers::ReceiverStream::new(content.into_receiver()).map(move |chunk| {sent+=chunk.len() as u64;event(json!({"type":"progress","id":progress_id,"bytes":sent,"size":size,"direction":"send"}));Ok::<_,std::io::Error>(chunk)});
                client.upload(ProtocolType::Https,&ip,port,None,&response.session_id,file_id,upload_token,localsend::reqwest::Body::wrap_stream(stream),token.clone()).await?;
            }
            Ok(())
        }.await;
        match outcome {Ok(())=>event(json!({"type":"sent","id":id})),Err(_)=>event(json!({"type":"error","id":id,"message":if token.is_cancelled(){"传输已取消"}else{"传输失败、超时或被拒绝；请确认同一网络和对方仍打开接收页面"}}))}
        shared().lock().unwrap().sends.remove(&id);
    });
    Ok(json!({"ok":true,"id":result_id}))
}
async fn command(args: Value) -> Result<Value> {
    match args["command"].as_str().unwrap_or("") {
        "start" => start(args).await,
        "poll" => Ok(json!({"ok":true,"events":shared().lock().unwrap().events.drain(..).collect::<Vec<_>>()})),
        "scan" => {
            let (generation, ready) = { let s = shared().lock().unwrap(); (s.generation, s.multicast.is_some()) };
            if !ready { bail!("局域网发现未启动，请重新打开本页") }
            schedule_announce(generation);
            Ok(json!({"ok":true}))
        }
        "probe" => {
            let ip = safe_ip(args["ip"].as_str().context("IP required")?)?;
            // 端口必须真校验：以前是 `as u16` 直接截断，填 70000 会变成 4464
            let port = u16::try_from(args["port"].as_u64().unwrap_or(53317)).context("端口需要在 1–65535 之间")?;
            if port == 0 { bail!("端口需要在 1–65535 之间") }
            // 异步执行。以前这里是 `.await` 整个握手 —— 最长 5 秒的超时全压在这一次 JNI 调用里，
            // 轮询事件、取消传输、决定接收全被锁住，界面看着就是「卡住」。现在立刻返回，结果走事件。
            tokio::spawn(async move { let _ = discover(ip, port, None, true).await; });
            Ok(json!({"ok":true,"async":true}))
        }
        "decide" => { let id=args["id"].as_str().context("Session required")?;let p=shared().lock().unwrap().pending.remove(id).context("Request expired")?;
            let accept=args["accept"].as_bool().unwrap_or(false);
            let decision=if accept {let ids:HashSet<_>=p.files.keys().cloned().collect();shared().lock().unwrap().accepted.insert(id.to_owned(),p.files);PrepareUploadDecisionV2::Accept(ids)}else{PrepareUploadDecisionV2::Decline};
            let _=p.decision.send(decision);Ok(json!({"ok":true})) }
        "send" => send(args).await,
        "cancel" => { let id=args["id"].as_str().context("Session required")?;
            let target={let s=shared().lock().unwrap();s.sends.get(id).map(|v| {v.token.cancel();(v.ip.clone(),v.port,v.session.clone())})};
            if let Some((ip,port,Some(session)))=target { if let Ok(c)=client(None,5) {let _=c.cancel(ProtocolType::Https,&ip,port,&session).await;} }
            let server=shared().lock().unwrap().server.clone();if let Some(s)=server {s.cancel_v2_session(id).await;}
            Ok(json!({"ok":true})) }
        "stop" => { let mut s=shared().lock().unwrap();if args["owner"].as_str().unwrap_or("")!=s.owner {return Ok(json!({"ok":true}))};s.generation+=1;for v in s.sends.values(){v.token.cancel()};s.pending.clear();s.accepted.clear();s.multicast_stop.take();s.server_stop.take();s.server=None;s.multicast=None;Ok(json!({"ok":true})) }
        _ => bail!("Unknown command"),
    }
}
#[no_mangle]
pub extern "system" fn Java_cn_tonglu_app_transfer_LocalSendNative_invoke(mut env:JNIEnv, _object:JObject, request:JString) -> jstring {
    init_tracing();
    let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<Value> {
        let raw:String=env.get_string(&request)?.into();let args:Value=serde_json::from_str(&raw)?;
        runtime().block_on(command(args))
    }));
    let output=match result {Ok(Ok(v))=>v,Ok(Err(e))=>json!({"ok":false,"message":e.to_string()}),Err(_)=>json!({"ok":false,"message":"Native transfer unavailable"})};
    env.new_string(output.to_string()).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}
