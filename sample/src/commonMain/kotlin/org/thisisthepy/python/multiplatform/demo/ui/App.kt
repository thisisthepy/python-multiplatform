package org.thisisthepy.python.multiplatform.demo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.thisisthepy.python.multiplatform.demo.PythonDemo
import org.thisisthepy.python.multiplatform.demo.UpcallDemo
import python.multiplatform.PlatformType
import python.multiplatform.currentPlatform

/**
 * One section per thing the library can do that it could not do when this sample was last touched.
 * Deliberately plain: the interesting part is what each button reaches, not the layout.
 *
 * Sections 1-4 are the original four: the interpreter, the object model, the raw upcall table, and
 * proof the processor ran. Sections 5-7 are the surface that grew *after* those and had no consumer
 * outside `python-multiplatform`'s own tests -- a Kotlin class constructed and driven from Python,
 * a companion reached through a metaclass, and `await` over a `suspend fun`. Where a target has no
 * boundary shim they report that rather than being hidden, because "unavailable here" is the
 * finding on those targets.
 *
 * Every composable in this app lives under this package because `build.gradle.kts` excludes it
 * from the upcall table -- a `@Composable` cannot be called from a generated non-composable
 * lambda.
 */
@Composable
@Preview
fun App() {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Python Multiplatform", style = MaterialTheme.typography.headlineSmall)

                RuntimeSection()
                EvaluateSection()
                UpcallSection()
                TableSection()
                ClassProxySection()
                StaticSurfaceSection()
                AwaitSection()
            }
        }
    }
}

/** 1. The interpreter is really up, and it is the build's configured one. */
@Composable
private fun RuntimeSection() {
    DemoCard("1, embedded interpreter") {
        val summary = remember { runCatching { PythonDemo.runtimeSummary() }.getOrElse { "not started: ${it.message}" } }
        Mono(summary)
    }
}

/** 2. Kotlin builds a Python object, Python computes with it, Kotlin reads the result back. */
@Composable
private fun EvaluateSection() {
    DemoCard("2, object model round trip") {
        var expression by remember { mutableStateOf(PythonDemo.DEFAULT_EXPRESSION) }
        var result by remember { mutableStateOf("") }

        Text("`kotlin_numbers` is a Python list built from a Kotlin List<Long> by PyList/PyInt.")
        OutlinedTextField(
            value = expression,
            onValueChange = { expression = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { result = PythonDemo.evaluate(expression) }) { Text("eval") }
        if (result.isNotEmpty()) Mono(result)
    }
}

/** 3. The direction that had no example at all until now: Python calling Kotlin. */
@Composable
private fun UpcallSection() {
    DemoCard("3, Python calls Kotlin (upcall)") {
        var presses by remember { mutableStateOf(0) }
        var answer by remember { mutableStateOf("") }

        if (!UpcallDemo.available) {
            Mono(UpcallDemo.callFromPython())
            return@DemoCard
        }

        Text("Python resolves \"${UpcallDemo.entryName}\" against the generated table, then calls it.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { UpcallDemo.press(); presses += 1 }) { Text("press ($presses)") }
            Spacer(Modifier.width(4.dp))
            Button(onClick = { answer = UpcallDemo.callFromPython() }) { Text("ask Python") }
        }
        if (answer.isNotEmpty()) Mono(answer)
    }
}

/** 4. Proof the KSP processor ran, read off the runtime table rather than off the build log. */
@Composable
private fun TableSection() {
    DemoCard("4, generated function table") {
        Mono(UpcallDemo.tableSummary())
        if (UpcallDemo.available) {
            Mono(
                if (UpcallDemo.optOutHeld()) {
                    "@PythonInternal held: the annotated member is absent from the table"
                } else {
                    "@PythonInternal did NOT hold -- the opt-out is broken"
                },
            )
        }
    }
}

/**
 * 5. Python constructing and driving a Kotlin object -- the shape none of sections 1-4 could show,
 * because `DemoCounter` is an `object` and has no constructor, no receiver and no properties.
 */
@Composable
private fun ClassProxySection() {
    DemoCard("5, Python drives a Kotlin class") {
        var result by remember { mutableStateOf("") }

        Mono(remember { PythonDemo.proxyInstallReport() })
        Text("g = Greeter('Kotlin'); g.greet(2); g.subject = 'Python'; g.greetings = 99")
        Button(onClick = { result = PythonDemo.classProxy() }) { Text("run") }
        if (result.isNotEmpty()) Mono(result)
    }
}

/** 6. The companion object, reached through the class and never through an instance. */
@Composable
private fun StaticSurfaceSection() {
    DemoCard("6, companion members on the metaclass") {
        var result by remember { mutableStateOf("") }

        Text("Greeter.built = 100; Greeter.forget(); Greeter.PUNCTUATION = '?'")
        Button(onClick = { result = PythonDemo.staticSurface() }) { Text("run") }
        if (result.isNotEmpty()) Mono(result)
    }
}

/**
 * 7. `await kotlin_fn(x)`.
 *
 * Two buttons because the two paths are deliberately indistinguishable from Python and only the
 * `create_future` count tells them apart: the fast one never reaches the event loop, the slow one
 * hands back a `Future` that a Kotlin thread settles.
 */
@Composable
private fun AwaitSection() {
    DemoCard("7, await over a suspend fun") {
        // Neither button below may run on wasmJs: PythonDemo.awaitFastPath() and
        // PythonDemo.awaitSuspending() both reach `import asyncio`, which traps this wasm
        // instance rather than raising (docs/design/upcall.md §5.8) -- there would be no
        // process left to show an error in. Gated here, before either is ever called, rather than
        // inside them: this is the one section the boundary itself cannot recover from.
        if (currentPlatform.platformType == PlatformType.Wasm) {
            Mono("unavailable on wasmJs: `import asyncio` traps this target instead of raising")
            return@DemoCard
        }

        var fast by remember { mutableStateOf("") }
        var slow by remember { mutableStateOf("") }

        Text("await g.greetNow(1) and await g.greetLater(2), same call site, two paths underneath.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { fast = PythonDemo.awaitFastPath() }) { Text("fast path") }
            Spacer(Modifier.width(4.dp))
            Button(onClick = { slow = PythonDemo.awaitSuspending() }) { Text("really suspends") }
        }
        if (fast.isNotEmpty()) Mono(fast)
        if (slow.isNotEmpty()) Mono(slow)
    }
}

@Composable
private fun DemoCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
}
