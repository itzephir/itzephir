"""Check the pinned image on a disposable CI runner, without logging its token."""
import base64
from pathlib import Path
import sys
from urllib.error import HTTPError
from urllib.request import Request, urlopen

token = Path(sys.argv[1]).read_text().strip().split("=", 1)[1]
authorization = "Basic " + base64.b64encode(f"open-design:{token}".encode()).decode()
for path in ("/", "/api/health", "/api/projects"):
    request = Request("http://127.0.0.1:7456" + path, headers={
        "Authorization": authorization,
        "Origin": "https://opendesign.itzephir.com",
    })
    with urlopen(request, timeout=15) as response:
        body = response.read()
        assert response.status == 200, "Authenticated OpenDesign request failed"
        if path == "/":
            assert b"<html" in body.lower(), "Official image must serve the editor UI"
    try:
        urlopen("http://127.0.0.1:7456" + path, timeout=15).close()
    except HTTPError as error:
        assert error.code == 401, "Unexpected anonymous response"
    else:
        # Upstream deliberately leaves health/readiness/version probes open.
        # nginx supplies the authentication boundary for every public path.
        assert path == "/api/health", "The editor and project API must enforce the daemon token"
print("Pinned OpenDesign image serves its UI and authenticated API.")
