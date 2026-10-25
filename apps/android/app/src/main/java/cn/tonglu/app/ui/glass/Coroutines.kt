/*
 * Copyright (c) 2026
 *
 * This file is derived from AndroidLiquidGlass (Backdrop):
 * https://github.com/Kyant0/AndroidLiquidGlass
 * Copyright (c) Kyant0 and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.tonglu.app.ui.glass

import androidx.compose.runtime.withFrameNanos

/**
 * 等待下一帧。
 *
 * 用于动画开始前确保当前帧已完成。
 *
 * 注：上游 skill 版本包了一层 `kotlinx.coroutines.android.awaitFrame`（并修过它的递归缺陷）；
 * 这里直接用 Compose 的 [withFrameNanos]，语义一致且不依赖协程库的内部实现。
 */
suspend fun awaitFrame() {
    withFrameNanos { }
}
