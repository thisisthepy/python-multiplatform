// No `package` line on purpose (issue #319): a root-package declaration cannot be named from the
// generated fragments, so the processor skips it with a warning instead of breaking this build.

fun rootPackageProbe(): Int = 7

class RootPackageThing
