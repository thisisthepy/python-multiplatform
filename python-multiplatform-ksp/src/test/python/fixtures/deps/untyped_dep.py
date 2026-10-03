# Stands in for a third-party package without stubs. It is deliberately unannotated: the gate
# must not report anything inside it, only where its untyped results flow into user code.
import json


def load(text):
    return json.loads(text)
