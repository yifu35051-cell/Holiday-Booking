"""Configure one trade's WireMock fault. Does not create payments or touch MySQL/money.

Examples (WireMock must already be running on loopback):
  python scripts/Set-PaymentScenario.py --trade HB... --operation authorize --scenario declined
  python scripts/Set-PaymentScenario.py --trade HB... --operation callback --scenario unavailable
  python scripts/Set-PaymentScenario.py --trade HB... --operation callback --scenario normal
"""

import argparse
import json
import re
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--trade", required=True, help="Exact outTradeNo shown in payment attempt")
    parser.add_argument("--operation", choices=["create", "query", "authorize", "close", "refund", "callback"], required=True)
    parser.add_argument("--scenario", choices=["normal", "declined", "unavailable", "slow", "unknown"], required=True)
    parser.add_argument("--port", type=int, default=9090)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", args.trade) or not 1 <= args.port <= 65535:
        parser.error("Invalid trade number or port")
    if args.operation == "callback" and args.scenario in ("declined", "unknown"):
        parser.error("Callbacks support normal/unavailable/slow; monetary state is never supplied by a stub")
    mapping_id = str(uuid.uuid5(uuid.NAMESPACE_URL, "holiday:" + args.operation + ":" + args.trade))
    origin = f"http://127.0.0.1:{args.port}"
    # Delete only this script's deterministic per-trade override, never reset other trades.
    request = urllib.request.Request(f"{origin}/__admin/mappings/{mapping_id}", method="DELETE")
    try:
        urllib.request.urlopen(request, timeout=5).close()
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
    if args.scenario == "normal":
        print("Removed override; shared normal mapping applies. Money and transaction history unchanged.")
        return
    path = "/api/v1/payment-callbacks/wiremock" if args.operation == "callback" else "/channel/" + args.operation
    response = {"status": 503}
    if args.scenario != "unavailable":
        response = {
            "status": 200,
            "headers": {"Content-Type": "application/json"},
            "jsonBody": {
                "operation": args.operation,
                "outTradeNo": args.trade,
                "decision": "DECLINED" if args.scenario == "declined" else "UNKNOWN" if args.scenario == "unknown" else "ACCEPTED",
            },
        }
        if args.scenario == "slow":
            if args.operation == "callback":
                # Delay acknowledgement after forwarding: exercises duplicate callback recovery.
                response = {"proxyBaseUrl": "{{systemValue type='ENVIRONMENT' key='CALLBACK_ORIGIN'}}", "transformers": ["response-template"]}
            response["fixedDelayMilliseconds"] = 15000
    data = {
        "id": mapping_id,
        "priority": 1,
        "metadata": {"owner": "holiday-payment-scenarios", "trade": args.trade},
        "request": {"method": "POST", "urlPath": path, "bodyPatterns": [
            {"matchesJsonPath": {"expression": "$.outTradeNo", "equalTo": args.trade}}
        ]},
        "response": response,
    }
    request = urllib.request.Request(f"{origin}/__admin/mappings", json.dumps(data).encode(), {"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(request, timeout=5) as result:
        if result.status != 201:
            raise RuntimeError("WireMock did not accept mapping")
    print(f"Configured {args.operation}/{args.scenario} for {args.trade}; no wallet or booking changed.")


if __name__ == "__main__":
    main()
