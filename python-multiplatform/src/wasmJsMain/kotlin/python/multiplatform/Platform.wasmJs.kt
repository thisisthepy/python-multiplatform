package python.multiplatform


object WasmPlatform : Platform {
    override val os = OSType.Web
    override val arch: String = "wasm32"
    override val version: String = "emscripten"
    override val versionCode: Int? = null
    override val platformType = PlatformType.Wasm
    override val platformVersion: String = "Kotlin/Wasm"

    override fun toString(): String = name
}

actual val currentPlatform: Platform = WasmPlatform
