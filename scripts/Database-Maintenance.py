"""Explicit MySQL backup and retention preview. Never starts Docker or alters business rows.

  python scripts/Database-Maintenance.py backup --mode Development
  python scripts/Database-Maintenance.py retention-preview --mode Docker
  python scripts/Database-Maintenance.py verify-backup --file .local/backups/<name>.sql

Restore rehearsal procedure is documented in the design baseline. This utility intentionally
does not overwrite an existing database or automatically delete personally identifiable data.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
BACKUPS = ROOT / ".local" / "backups"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["backup", "retention-preview", "verify-backup"])
    parser.add_argument("--mode", choices=["Development", "Docker"], default="Development")
    parser.add_argument("--file", type=Path)
    args = parser.parse_args()
    if args.action == "verify-backup":
        if not args.file:
            parser.error("--file is required")
        path = args.file.resolve(strict=True)
        if not path.is_relative_to(BACKUPS.resolve()):
            parser.error("Only locally generated .local/backups files are accepted")
        meta = json.loads(path.with_suffix(".json").read_text(encoding="utf-8"))
        if digest(path) != meta["sha256"]:
            raise SystemExit("Backup checksum mismatch")
        print("Checksum verified; a separate isolated restore rehearsal is still required.")
        return
    development = args.mode == "Development"
    command = ["docker", "compose", "--env-file", str(ROOT / "config" / ("development.env" if development else "deployment.env")),
               "-f", str(ROOT / "config" / ("compose.dev.yaml" if development else "compose.yaml")), "exec", "-T", "mysql", "sh", "-c"]
    if args.action == "backup":
        BACKUPS.mkdir(parents=True, exist_ok=True)
        path = BACKUPS / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + ".sql")
        # Credentials expand inside the container only, never into the host argv or output.
        command += ['export MYSQL_PWD="$MYSQL_PASSWORD"; exec mysqldump --single-transaction --no-tablespaces --skip-routines --triggers --set-gtid-purged=OFF -u"$MYSQL_USER" "$MYSQL_DATABASE"']
        with path.open("xb") as output:
            result = subprocess.run(command, stdout=output, stderr=subprocess.PIPE, check=False)
        if result.returncode:
            path.unlink(missing_ok=True)
            raise SystemExit("MySQL backup failed; no completed backup was retained. Check container and database permissions.")
        meta = {"mode": args.mode, "sha256": digest(path), "bytes": path.stat().st_size,
                "createdAt": datetime.now(timezone.utc).isoformat(), "restoreVerified": False}
        path.with_suffix(".json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
        print(f"Backup saved privately: {path.relative_to(ROOT)}. Not a verified restore or an encrypted archive.")
    else:
        # Preview only; no speculative deletion of financial/audit/idempotency history.
        query = ("SELECT 'mail_body_redaction_candidates_90d',count(*) FROM mail_message "
                 "WHERE sender_deleted=1 AND recipient_deleted=1 AND state IN ('SENT','SIMULATED','FAILED') "
                 "AND created_at < UTC_TIMESTAMP()-INTERVAL 90 DAY; "
                 "SELECT 'expired_unused_quotes_30d',count(*) FROM booking_quote "
                 "WHERE consumed_at IS NULL AND expires_at < UTC_TIMESTAMP()-INTERVAL 30 DAY;")
        command += ['export MYSQL_PWD="$MYSQL_PASSWORD"; exec mysql --batch --skip-column-names -u"$MYSQL_USER" "$MYSQL_DATABASE" -e \'' + query + "'"]
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
        if result.returncode:
            raise SystemExit("Retention preview failed; check container/database. No changes were made.")
        print(result.stdout.decode("utf-8"), end="")
        print("Preview only. No rows deleted and no personal data modified.")


if __name__ == "__main__":
    main()
