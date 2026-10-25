package cn.tonglu.app.transfer

/** Adapter to the vendored LocalSend Rust core, not a second implementation of its protocol. */
internal object LocalSendNative {
    init { System.loadLibrary("campus_localsend") }
    @Synchronized external fun invoke(command: String): String
}
