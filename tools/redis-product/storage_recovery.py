"""Check the unconfirmed receipt contract when the owned PostgreSQL process is stopped."""

import json
import os
import time

import redis_lab as R
from check import check, http, post, until

L = R.L


def verify():
    lab = L.state()
    L.validate_fixture_date(lab)
    L.inspect_db(lab)
    db = "rb0_product" + str(time.time_ns())
    folder = L.OUT / (db + "-storage-recovery")
    folder.mkdir()
    L.sql(lab, L.ROLE, f"CREATE DATABASE {db} TEMPLATE {L.TEMPLATE}")
    os.environ["CT_PRODUCT_CONFIG"] = json.dumps(
        {"waiting": {"dispatch": {"enabled": False}}}
    )
    with L.apps(lab, db, 1, folder) as (ports, _):
        port = ports[0]
        check(
            L.sql(lab, db, "SELECT enable_waiting_intake(1,current_date,3)"), "ENABLED"
        )
        created, original, url, _ = post(port, 940)
        check(created, 202)
        L.inspect_db(lab)
        L.docker("stop", lab["id"])
        try:
            unresolved = http(port, "GET", url, 940)
            retry = post(port, 941)
            for code, body, _, delay in (unresolved, retry):
                check(
                    (code, body["code"], delay),
                    (503, "WAITING_RECEIPT_UNCONFIRMED", "2"),
                )
        finally:
            L.docker("start", lab["id"])
        # A failing JDBC acquisition remains retriable during Hikari's reconnect interval.
        replay = until(lambda: post(port, 940), lambda response: response[0] == 200, 30)
        check(
            (replay[1]["receiptId"], replay[1]["intakeSequence"]),
            (original["receiptId"], 1),
        )
        new, issued, second_url, _ = post(port, 941)
        check((new, issued["intakeSequence"]), (202, 2))
        for member, location in ((940, url), (941, second_url)):
            check(http(port, "DELETE", location, member)[1]["status"], "WITHDRAWN")
        check(
            L.sql(lab, db, "SELECT count(*) FROM waiting_receipt WHERE store_id=1"), "2"
        )
        check(
            L.sql(
                lab, db, "SELECT pending_count FROM waiting_intake_day WHERE store_id=1"
            ),
            "0",
        )
        result = {
            "jar": L.digest(L.JAR),
            "lookup_db_down": 503,
            "post_db_down": 503,
            "retry_after_s": 2,
            "original_replay_http": 200,
            "same_receipt": True,
            "new_key_sequence": 2,
            "unfinished_after_withdrawals": 0,
        }
        L.save(folder / "storage.json", result)
        print(folder.name, result, flush=True)


if __name__ == "__main__":
    with L.lock():
        verify()
