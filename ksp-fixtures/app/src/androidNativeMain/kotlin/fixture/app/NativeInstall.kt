package fixture.app

/**
 * The half of ROADMAP §13's first defect that `commonMain` alone does not reproduce.
 *
 * `androidNativeMain` is an **intermediate** source set: `androidNativeArm64Main` and
 * `androidNativeX64Main` depend on it, and KSP generates into those two, not into this one. So
 * this file can neither name `python.multiplatform.generated.FunctionTable` nor host the `actual`
 * that does -- exactly the position `sample`'s `iosMain` is in, which is where the defect was
 * found. Before the seam, the only way out was one hand-written `actual` per leaf.
 *
 * That it compiles for both leaves is the whole assertion; there is no test binary here.
 * Verified with `:ksp-fixtures:app:compileKotlinAndroidNativeArm64` and `...X64`.
 */
fun installOnNative(): String = installAndDescribeTable()
