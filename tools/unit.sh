#!/usr/bin/env bash
# Runs the JVM unit tests and prints a one-line summary plus any failures.
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-17.0.6.10-hotspot}"
./gradlew.bat :app:testDebugUnitTest --console=plain -q 2>&1 | grep -E "^e: |What went|BUILD FAILED" | head -30
python - <<'PY'
import glob,xml.etree.ElementTree as ET
t=f=0
for p in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
    r=ET.parse(p).getroot(); t+=int(r.get('tests')); f+=int(r.get('failures'))+int(r.get('errors'))
    for c in r.iter('testcase'):
        for x in list(c):
            if x.tag in('failure','error'): print('FAIL',c.get('name'),(x.get('message') or '')[:400])
print('unit tests',t,'failed',f)
PY
