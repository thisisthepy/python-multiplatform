package org.thisisthepy.python.multiplatform.demo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

/**
 * Lives in the `ui` package with every other composable: `build.gradle.kts` excludes that package
 * from the upcall table, because a generated entry would call a `@Composable` from a plain lambda
 * and the generated file would not compile.
 */
@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
