#!/usr/bin/env bash
# Smoke test of the plugin zip in a real Open Integration Engine: unpacks the engine release,
# installs the zip, starts the server, deploys the channel of .github/smoke/SmokeChannel.java
# (HTTP Listener, data type FHIR with Resource Types "Patient") and posts four messages:
#
#   valid Patient (JSON)      200, OperationOutcome in JSON
#   valid Patient (XML)       200, OperationOutcome in XML
#   invalid Patient           400, the validation issues
#   Observation               400, "not in Resource Types"
#
# each with the status and Content-Type the auto responder sets (${fhirHttpStatus},
# ${fhirContentType} in the listener's response settings, #33).
#
# This covers what the unit tests cannot: the engine loads the extension (exact mirthVersion),
# HAPI runs in the plugin's isolated lib/ class loader, and the heap is enough.
#
# Usage: smoke-test.sh <engine version, e.g. 4.7.0> <plugin zip>
# Environment: JAVA (default java), OIE_DOWNLOADS (default ~/.cache/oie), SMOKE_DIR (default a
# temporary folder, kept on failure), SMOKE_HTTP_PORT / SMOKE_HTTPS_PORT (default 8080 / 8443),
# SMOKE_LISTENER_PORT (default 8090).
set -euo pipefail

version="${1:?engine version, e.g. 4.7.0}"
zip="$(cd "$(dirname "${2:?plugin zip}")" && pwd)/$(basename "$2")"
repo="$(cd "$(dirname "$0")/../.." && pwd)"
java="${JAVA:-java}"
http_port="${SMOKE_HTTP_PORT:-8080}"
https_port="${SMOKE_HTTPS_PORT:-8443}"
listener_port="${SMOKE_LISTENER_PORT:-8090}"
password="smoke-test-admin-password"
channel_id="5e0b7e5f-f41c-4d1e-9a6c-000000000001"
work="${SMOKE_DIR:-$(mktemp -d)}"
oie="${work}/oie"
api="https://localhost:${https_port}/api"
jar="${work}/cookies.txt"
sep=":"
native() { echo "$1"; }
case "$(uname -s)" in MINGW* | MSYS* | CYGWIN*) sep=";"; native() { cygpath -m "$1"; } ;; esac

server_pid=""
stop_server() {
  if [ -n "${server_pid}" ] && kill -0 "${server_pid}" 2>/dev/null; then
    echo "Stopping the engine"
    kill "${server_pid}" 2>/dev/null || true
    for _ in $(seq 1 30); do kill -0 "${server_pid}" 2>/dev/null || break; sleep 1; done
    kill -9 "${server_pid}" 2>/dev/null || true
  fi
}
fail() {
  echo "SMOKE TEST FAILED: $*" >&2
  echo "--- last lines of the server log (${oie}/logs/mirth.log)" >&2
  tail -n 60 "${oie}/logs/mirth.log" 2>/dev/null >&2 || tail -n 60 "${work}/server.out" >&2 || true
  exit 1
}
trap stop_server EXIT

# ------------------------------------------------------------------ engine with the plugin

tarball="$(bash "${repo}/.github/scripts/download-oie.sh" "${version}" "${OIE_DOWNLOADS:-${HOME}/.cache/oie}")"
echo "Unpacking ${tarball} in ${work}"
rm -rf "${oie}"
mkdir -p "${work}"
tar -xzf "${tarball}" -C "${work}"
rm -rf "${oie}/extensions/datatype-fhir"
unzip -q "${zip}" -d "${oie}/extensions"
plugin_version="$(sed -n 's|.*<pluginVersion>\(.*\)</pluginVersion>.*|\1|p' "${oie}/extensions/datatype-fhir/plugin.xml")"
mirth_versions="$(sed -n 's|.*<mirthVersion>\(.*\)</mirthVersion>.*|\1|p' "${oie}/extensions/datatype-fhir/plugin.xml")"
echo "Plugin ${plugin_version}, mirthVersion ${mirth_versions}, engine ${version}"
case ",${mirth_versions}," in *",${version},"*) ;; *) fail "plugin.xml mirthVersion (${mirth_versions}) does not list ${version}: the engine would not load it" ;; esac

props="${oie}/conf/mirth.properties"
sed -i.bak -e "s|^http.port *=.*|http.port = ${http_port}|" -e "s|^https.port *=.*|https.port = ${https_port}|" -e '/^server.initialadminpassword/d' "${props}"
echo "server.initialadminpassword = ${password}" >> "${props}"

# The options of the engine's own launcher (oieserver.vmoptions), with 1 GB of heap: the package
# default of 256 MB is not enough for the FHIR specification (see the README).
args="${work}/java-options.txt"
: > "${args}"
for f in conf/base_includes.vmoptions conf/default_modules.vmoptions; do
  [ -f "${oie}/${f}" ] && grep -v -e '^\s*#' -e '^\s*$' -e '^-include-options' -e '^-Xmx' "${oie}/${f}" | tr -d '\r' >> "${args}"
done
echo "-Xmx1g" >> "${args}"

echo "Starting the engine ($("${java}" -version 2>&1 | head -1))"
(cd "${oie}" && exec "${java}" "@$(native "${args}")" -jar mirth-server-launcher.jar > "${work}/server.out" 2>&1) &
server_pid=$!

curl_api() { curl -sk -b "${jar}" -c "${jar}" -H "X-Requested-With: smoke-test" "$@"; }

echo "Waiting for the engine to accept a login"
logged_in=""
for _ in $(seq 1 120); do
  kill -0 "${server_pid}" 2>/dev/null || fail "the engine stopped while starting"
  for pw in "${password}" admin; do
    code="$(curl_api -o /dev/null -w '%{http_code}' -X POST "${api}/users/_login" --data-urlencode username=admin --data-urlencode "password=${pw}" || true)"
    if [ "${code}" = "200" ]; then logged_in=1; break 2; fi
  done
  sleep 3
done
[ -n "${logged_in}" ] || fail "no login within 6 minutes"
echo "Logged in"

code="$(curl_api -o "${work}/plugins.xml" -w '%{http_code}' -H "Accept: application/xml" "${api}/extensions/plugins" || true)"
grep -q "<name>FHIR Data Type</name>" "${work}/plugins.xml" \
  || { echo "HTTP ${code}" >&2; head -c 2000 "${work}/plugins.xml" >&2; echo >&2; fail "the engine did not load the FHIR Data Type extension"; }
echo "Extension loaded"

# ------------------------------------------------------------------ channel

o="$(native "${oie}")"
# The engine jars first: they contain their own public copies of Rhino classes such as NativeDate,
# which must win over server-lib/rhino-*.jar. A wildcard lists jars in directory order, which
# differs between Windows and Linux.
cp="${o}/server-lib/mirth-client-core.jar${sep}${o}/server-lib/mirth-server.jar${sep}${o}/server-lib/*${sep}${o}/server-lib/donkey/*${sep}${o}/server-lib/commons/*${sep}${o}/server-lib/log4j/*${sep}${o}/extensions/http/*${sep}${o}/extensions/vm/*${sep}${o}/extensions/datatype-fhir/datatype-fhir-shared.jar"
"${java}" -cp "${cp}" "$(native "${repo}/.github/smoke/SmokeChannel.java")" "${version}" "${listener_port}" "$(native "${work}/channel.xml")" > "${work}/generator.out" 2>&1 \
  || { cat "${work}/generator.out" >&2; fail "could not generate the channel"; }

code="$(curl_api -o "${work}/import.out" -w '%{http_code}' -X POST "${api}/channels" -H "Content-Type: application/xml" --data-binary "@${work}/channel.xml")"
[ "${code}" = "200" ] || { cat "${work}/import.out" >&2; fail "channel import returned HTTP ${code}"; }
code="$(curl_api -o "${work}/deploy.out" -w '%{http_code}' -X POST "${api}/channels/${channel_id}/_deploy")"
[ "${code}" = "204" ] || [ "${code}" = "200" ] || { cat "${work}/deploy.out" >&2; fail "deploy returned HTTP ${code}"; }

started=""
for _ in $(seq 1 60); do
  if curl_api -H "Accept: application/xml" "${api}/channels/${channel_id}/status" | grep -q "<state>STARTED</state>"; then started=1; break; fi
  sleep 2
done
[ -n "${started}" ] || fail "the channel did not start"
echo "Channel deployed"

# ------------------------------------------------------------------ messages

post() { # <name> <content type> <file> <expected status> <expected text> [expected content type]
  local status
  status="$(curl -s -o "${work}/$1.response" -D "${work}/$1.headers" -w '%{http_code}' -X POST "http://localhost:${listener_port}/" \
    -H "Content-Type: $2" --data-binary "@$3" --max-time 120)"
  if [ "${status}" != "$4" ] || ! grep -q "$5" "${work}/$1.response"; then
    echo "--- response" >&2; cat "${work}/$1.response" >&2; echo >&2
    fail "$1: expected HTTP $4 with '$5', got HTTP ${status}"
  fi
  if [ -n "${6:-}" ] && ! grep -qi "^content-type: $6" "${work}/$1.headers"; then
    cat "${work}/$1.headers" >&2
    fail "$1: expected Content-Type $6"
  fi
  echo "OK  $1: HTTP ${status}"
}

cat > "${work}/patient.xml" <<'XML'
<Patient xmlns="http://hl7.org/fhir"><active value="true"/><name><family value="Jansen"/></name><gender value="male"/></Patient>
XML
cat > "${work}/observation.json" <<'JSON'
{"resourceType":"Observation","status":"final","code":{"coding":[{"system":"http://loinc.org","code":"8867-4"}]}}
JSON

post patient-json application/fhir+json "${repo}/examples/patient.json" 200 '"resourceType":"OperationOutcome"' application/fhir+json
post patient-xml application/fhir+xml "${work}/patient.xml" 200 '<OperationOutcome' application/fhir+xml
post invalid-patient application/fhir+json "${repo}/examples/patient-invalid.json" 400 'shoeSize' application/fhir+json
post observation application/fhir+json "${work}/observation.json" 400 'Resource type Observation is not in Resource Types (Patient)' application/fhir+json

if grep -E "FHIR engine is not available|NoClassDefFoundError|ClassNotFoundException|OutOfMemoryError" "${oie}/logs/mirth.log" "${work}/server.out" 2>/dev/null; then
  fail "the server log shows a class loading or memory problem"
fi

echo "Smoke test passed: plugin ${plugin_version} on OIE ${version}"
