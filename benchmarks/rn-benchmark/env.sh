# Source this before any node/npm/pod command in this project.
#
# No path outside this repository is hard-coded here. Toolchains that live elsewhere on a given
# machine are named through environment variables; caches this benchmark creates itself go under
# `.caches/` next to this file (git-ignored).
_RN_BENCH_HERE="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"

# Node: set RN_BENCH_NODE_HOME to a Node install (e.g. emsdk's bundled node) when the one on PATH
# is not the one to use.
if [ -n "${RN_BENCH_NODE_HOME:-}" ]; then
  export NODE_HOME="$RN_BENCH_NODE_HOME"
  export PATH="$NODE_HOME/bin:$PATH"
fi
export npm_config_cache="${npm_config_cache:-$_RN_BENCH_HERE/.caches/npm}"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

# --- Ruby / CocoaPods -------------------------------------------------------------------------
# On the machine this was first run on, the system rbenv Ruby 3.2.2 was unusable: it had been
# installed under a previous account name and every Mach-O in it, plus every shebang, pointed at the
# old home directory, so `pod` failed with "bad interpreter". The fix was a relocated copy with its
# install names and shebangs repaired, and RUBYLIB set because the interpreter's compiled-in default
# load path still pointed at the old prefix. Point RN_BENCH_RUBY_HOME at such a copy if you need it.
export GEM_HOME="${GEM_HOME:-$_RN_BENCH_HERE/.caches/gems}"
if [ -n "${RN_BENCH_RUBY_HOME:-}" ]; then
  export RUBY_HOME="$RN_BENCH_RUBY_HOME"
  export RUBYLIB="$RUBY_HOME/lib/ruby/3.2.0:$RUBY_HOME/lib/ruby/3.2.0/arm64-darwin23"
  export GEM_PATH="$GEM_HOME:$RUBY_HOME/lib/ruby/gems/3.2.0"
  export PATH="$RUBY_HOME/bin:$PATH"
fi
export PATH="$GEM_HOME/bin:$PATH"
unset _RN_BENCH_HERE
