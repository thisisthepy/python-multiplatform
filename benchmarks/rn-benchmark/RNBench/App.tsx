/**
 * The whole app is the benchmark. It runs once on mount, prints the report with `console.log` so
 * `adb logcat` / `xcrun simctl spawn ... log stream` can capture it without a debugger attached,
 * and shows the same text on screen.
 *
 * Every line of the report is printed separately: a single long write is truncated by logcat, and
 * the reference project hit exactly that when its report had to survive into JUnit XML.
 *
 * On iOS these arrive in the unified log at **Info** level, under the subsystem/category
 * `com.facebook.react.log:javascript`. `log stream` defaults to `--level default`, which drops
 * Info, so a reader without `--level info` sees a perfectly empty capture of a perfectly healthy
 * run. That cost two runs; see the note on the capture in run-bench.sh before changing how this
 * function emits anything.
 */
import React, {useCallback, useEffect, useState} from 'react';
import {
  ActivityIndicator,
  Button,
  Platform,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
} from 'react-native';

const appRun = require('./bench/appRun.js');

const TAG = 'RNBENCH';

function loadBench(): {module: any | null; error: string | null} {
  try {
    // Required lazily: if codegen or autolinking did not produce the module, the app must still
    // start and say so, rather than dying at import time with a stack nobody can read on a device.
    return {module: require('react-native-bench').Bench, error: null};
  } catch (e) {
    return {module: null, error: String(e)};
  }
}

function App(): React.JSX.Element {
  const [report, setReport] = useState<string>('');
  const [running, setRunning] = useState<boolean>(false);

  const go = useCallback(async () => {
    setRunning(true);
    setReport('');
    const {module, error} = loadBench();
    let text: string;
    try {
      // Progress lines go out immediately and are prefixed `#`, so a run that stalls shows which
      // row it stalled on. A benchmark that only prints at the end is indistinguishable from a
      // hung one, and the 1000-element rows take tens of seconds each.
      const result = await appRun.run(module, {
        log: (l: string) => console.log(TAG + '| # ' + l),
        // So the report can name its own callee. appRun.js must not import from 'react-native'
        // itself -- it is concatenated into a plain script for the standalone Hermes run.
        platform: Platform.OS,
      });
      text = result.text;
    } catch (e) {
      text =
        'BENCHMARK THREW: ' +
        String(e) +
        (e instanceof Error ? '\n' + e.stack : '');
    }
    if (error) {
      text = 'react-native-bench did not load: ' + error + '\n' + text;
    }
    const outLines = text.split('\n');
    for (let i = 0; i < outLines.length; i++) {
      console.log(TAG + '| ' + outLines[i]);
    }
    console.log(TAG + '| END');
    setReport(text);
    setRunning(false);
  }, []);

  useEffect(() => {
    // Auto-run so a CI-style runner needs no interaction.
    go();
  }, [go]);

  return (
    <SafeAreaView style={styles.root}>
      <Button
        title={running ? 'running...' : 'run again'}
        onPress={go}
        disabled={running}
      />
      {running ? <ActivityIndicator style={styles.spinner} /> : null}
      <ScrollView horizontal>
        <ScrollView>
          <Text style={styles.mono}>{report}</Text>
        </ScrollView>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, padding: 8},
  spinner: {margin: 8},
  mono: {fontFamily: 'Courier', fontSize: 10},
});

export default App;
