package com.rnbench.bench

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

/**
 * Autolinked. The RN Gradle plugin finds this through `getPackages()` in the generated
 * `PackageList`, which it builds from the CLI's autolinking output.
 */
class BenchPackage : BaseReactPackage() {

    override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
        if (name == BenchModule.NAME) BenchModule(reactContext) else null

    override fun getReactModuleInfoProvider(): ReactModuleInfoProvider = ReactModuleInfoProvider {
        mapOf(
            BenchModule.NAME to ReactModuleInfo(
                BenchModule.NAME,
                BenchModule.NAME,
                false, // canOverrideExistingModule
                false, // needsEagerInit
                false, // isCxxModule
                true,  // isTurboModule
            ),
        )
    }
}
