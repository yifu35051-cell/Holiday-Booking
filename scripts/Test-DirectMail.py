"""Check configured SMTP without Spring Boot, MySQL or Docker.

Default: validate configuration, TLS and SMTP authentication only.
With --recipient: send exactly one verification message; never retry an uncertain send.
Credentials are read from the ignored env file and never written to the report.
"""

import argparse
from datetime import datetime, timezone
from email.message import EmailMessage
from email.utils import format_datetime, make_msgid, parseaddr
import json
from pathlib import Path
import re
import smtplib
import socket
import ssl
import sys


ROOT = Path(__file__).resolve().parents[1]


def configuration(path):
    values = {}
    for number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        match = re.fullmatch(r"([A-Z][A-Z0-9_]*)\s*=\s*(.*)", line)
        if not match:
            raise ValueError(f"Invalid dotenv syntax at line {number}")
        key, value = match.groups()
        value = value.strip()
        if key in values:
            raise ValueError(f"Duplicate configuration key: {key}")
        if value.startswith("'"):
            if not re.fullmatch(r"'[^']*'", value):
                raise ValueError(f"Unsupported quoted value for {key}")
            value = value[1:-1]
        elif not re.fullmatch(r"[A-Za-z0-9_./:@+-]*", value):
            raise ValueError(f"Use single quotes around {key}")
        values[key] = value
    return values


def masked(address):
    local, _, domain = address.partition("@")
    return (local[:1] + "***@" + domain) if domain else "<not configured>"


def run(args):
    now = datetime.now(timezone.utc)
    stamp = now.strftime("%Y%m%dT%H%M%S%fZ")
    report = {"timeUtc": now.isoformat(), "steps": [], "deliveryConfirmed": False}
    report_path = ROOT / "results" / f"directmail-{stamp}.json"
    stage = "CONFIG"
    client = None

    def passed(name, **details):
        report["steps"].append({"stage": name, "status": "PASSED", **details})
        print(f"[PASS] {name}")

    try:
        config = configuration(args.config)
        host = config.get("MAIL_HOST", "")
        port = int(config.get("MAIL_PORT", "465"))
        username = config.get("MAIL_USERNAME", "")
        password = config.get("MAIL_PASSWORD", "")
        sender = config.get("MAIL_FROM", "")
        if not host or not username or not password or not sender:
            raise ValueError("MAIL_HOST, MAIL_USERNAME, MAIL_PASSWORD and MAIL_FROM are required")
        if config.get("EMAIL_MODE") != "smtp" or config.get("MAIL_SMTP_AUTH") != "true":
            raise ValueError("EMAIL_MODE=smtp and MAIL_SMTP_AUTH=true are required")
        implicit = config.get("MAIL_SSL_ENABLE") == "true"
        starttls = config.get("MAIL_STARTTLS") == "true"
        if implicit == starttls:
            raise ValueError("Choose exactly one of implicit TLS or STARTTLS")
        if args.recipient and ("\r" in args.recipient or "\n" in args.recipient or
                               parseaddr(args.recipient)[1] != args.recipient or
                               "@" not in args.recipient):
            raise ValueError("Recipient must be one plain email address")
        report.update({"host": host, "port": port, "sender": masked(sender),
                       "recipient": masked(args.recipient) if args.recipient else None,
                       "mode": "send-one" if args.recipient else "authentication-only"})
        passed("CONFIG")

        stage = "DNS"
        socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
        passed("DNS")

        stage = "CONNECT_TLS"
        context = ssl.create_default_context()
        if implicit:
            client = smtplib.SMTP_SSL(host, port, timeout=12, context=context)
            client.ehlo()
        else:
            client = smtplib.SMTP(host, port, timeout=12)
            client.ehlo()
            client.starttls(context=context)
            client.ehlo()
        passed("CONNECT_TLS", protocol=client.sock.version(), certificateVerified=True)

        stage = "AUTH"
        code, _ = client.login(username, password)
        passed("AUTH", smtpCode=code)
        report["authenticationConfirmed"] = True

        if args.recipient:
            message = EmailMessage()
            message["From"] = sender
            message["To"] = args.recipient
            message["Subject"] = f"Holiday Booking - DirectMail verification - {stamp}"
            message["Date"] = format_datetime(now)
            message["Message-ID"] = make_msgid(domain=sender.rsplit("@", 1)[-1])
            message.set_content(
                "Holiday Booking DirectMail verification.\n\n"
                "This single message was sent by the standalone SMTP verification script.\n"
                "No Docker, database, Spring Boot or Mailpit was used.\n"
                f"Verification ID: {stamp}\n\n"
                "SMTP acceptance is not proof of inbox delivery. Please check your inbox and spam folder.\n"
            )
            report["verificationId"] = stamp
            report["subject"] = str(message["Subject"])
            stage = "SEND"
            refused = client.send_message(message, from_addr=sender, to_addrs=[args.recipient])
            if refused:
                raise smtplib.SMTPRecipientsRefused(refused)
            passed("SMTP_ACCEPTED")
            report["sendStatus"] = "SMTP_ACCEPTED"
            print("[INFO] Inbox delivery awaits recipient confirmation; check spam too.")
        else:
            report["sendStatus"] = "NOT_REQUESTED"
        report["status"] = "PASSED"
    except Exception as error:
        # Do not print SMTP dialogue: AUTH payloads contain reusable credentials.
        report["status"] = "FAILED"
        details = {"stage": stage, "status": "FAILED", "errorType": type(error).__name__}
        if isinstance(error, ValueError):
            details["reason"] = str(error)
        if isinstance(error, smtplib.SMTPResponseException):
            details["smtpCode"] = error.smtp_code
        if isinstance(error, OSError):
            details["osError"] = error.errno
            details["windowsError"] = getattr(error, "winerror", None)
        report["steps"].append(details)
        if stage == "SEND":
            report["sendStatus"] = (
                "REJECTED" if isinstance(error, smtplib.SMTPResponseException)
                else "UNKNOWN_DO_NOT_AUTOMATICALLY_RESEND"
            )
        print(f"[FAIL] {stage}: {type(error).__name__}")
        if isinstance(error, ValueError):
            print(str(error))
    finally:
        if client is not None:
            try:
                client.quit()
            except Exception:
                client.close()
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"Report: {report_path}")
    return 0 if report["status"] == "PASSED" else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=ROOT / "config/development.env")
    parser.add_argument("--recipient", help="Send one message to this user-controlled address")
    sys.exit(run(parser.parse_args()))
