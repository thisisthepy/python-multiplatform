package fixture.notebook

import androidx.compose.ui.Modifier

/**
 * The one line of Kotlin pythonx-compose asks an application for: where an empty `Modifier` comes from.
 *
 * `Modifier` as a Kotlin expression is `Modifier.Companion`, an object, and the walker binds functions,
 * so the empty modifier every `Modifier.padding(8)` chain starts from has no bound name of its own.
 * pythonx-compose's `pythonx/compose/ui/modifier.py` says what to do about it: supply a zero-argument
 * function returning `Modifier` and register its name with `install(...)`. KSP binds this one as
 * `fixture.notebook.emptyModifier`; `NotebookHost` registers it.
 */
fun emptyModifier(): Modifier = Modifier
