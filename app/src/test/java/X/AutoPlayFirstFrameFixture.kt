package X

/** 41's verified decoder callback has this obfuscated second parameter, not the synthetic resume signal. */
class `0Gf4`

class TestRenderDispatcher {
    @JvmField var completionListener: Runnable? = null
    fun completed(aid: String) { completionListener?.run() }
    fun playing(aid: String) {}
    fun paused(aid: String) {}
    fun onRenderFirstFrame(aid: String, info: `0Gf4`?) {}
    fun onRenderFirstFrameFromResume(aid: String) {}
}
