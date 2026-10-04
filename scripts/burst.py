#!/usr/bin/env python3
"""Exercise reservation contention and report response/inventory reconciliation."""

import argparse
import concurrent.futures
import json
import os
import statistics
import time
import urllib.error
import urllib.request
import uuid
from collections import Counter


def request(base_url, method, path, *, body=None, token=None, idempotency_key=None,
            accept="application/json", timeout=120):
    headers = {"Accept": accept}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode("utf-8")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    req = urllib.request.Request(base_url + path, data=data, headers=headers, method=method)
    started_at = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            status = response.status
            payload = response.read()
    except urllib.error.HTTPError as error:
        status = error.code
        payload = error.read()
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        return {"status": 0, "code": "transport_error", "error": str(error),
                "elapsed": time.perf_counter() - started_at}
    try:
        parsed = json.loads(payload) if payload else {}
    except json.JSONDecodeError:
        parsed = {"raw": payload.decode("utf-8", errors="replace")}
    return {"status": status, "code": parsed.get("code"), "body": parsed,
            "elapsed": time.perf_counter() - started_at}


def require_success(result, expected, operation):
    if result["status"] != expected:
        raise RuntimeError(f"{operation}: expected HTTP {expected}, got {result['status']}: {result.get('body', result.get('error'))}")
    return result["body"]


def sign_in(base_url, identifier, password, timeout):
    response = request(base_url, "POST", "/auth/signin",
                       body={"identifier": identifier, "password": password}, timeout=timeout)
    return require_success(response, 200, f"sign in {identifier}")["access_token"]


def create_show(base_url, admin_token, name, labels, limit, price_paise, timeout):
    response = request(base_url, "POST", "/shows", token=admin_token,
                       body={"name": name, "seats": labels, "price_paise": price_paise,
                             "per_user_limit": limit}, timeout=timeout)
    return require_success(response, 201, f"create show {name}")["id"]


def show_state(base_url, show_id, timeout):
    response = request(base_url, "GET", f"/shows/{show_id}", timeout=timeout)
    return require_success(response, 200, f"read show {show_id}")


def reserve(base_url, show_id, token, key, seats, timeout):
    return request(base_url, "POST", f"/shows/{show_id}/reserve", token=token,
                   idempotency_key=key, body={"seats": seats}, timeout=timeout)


def reserve_after(scheduled_at, delay, *args):
    wait_seconds = scheduled_at + delay - time.perf_counter()
    if wait_seconds > 0:
        time.sleep(wait_seconds)
    return reserve(*args)


DEFAULT_CONFIG = {
    "base_url": "http://localhost:8080",
    "attempts": 20000,
    "workers": 200,
    "clients": 200,
    "ramp_up_seconds": 0,
    "limit_race_requests": 5,
    "per_user_limit": 2,
    "price_paise": 100,
    "timeout_seconds": 120,
    "scenario": "all",
    "keep_users": False,
    "user_prefix": "burst",
    "user_domain": "example.test",
}


def load_settings(args, parser):
    settings = dict(DEFAULT_CONFIG)
    if args.config:
        try:
            with open(args.config, "r", encoding="utf-8") as config_file:
                supplied = json.load(config_file)
        except (OSError, json.JSONDecodeError) as error:
            parser.error(f"cannot read config file {args.config}: {error}")
        if not isinstance(supplied, dict):
            parser.error("config file must contain a JSON object")
        unknown = set(supplied) - set(settings)
        if unknown:
            parser.error(f"unknown config setting(s): {', '.join(sorted(unknown))}")
        settings.update(supplied)
    for key in settings:
        value = getattr(args, key, None)
        if value is not None:
            settings[key] = value
    if args.base_url is None and os.getenv("BASE_URL"):
        settings["base_url"] = os.environ["BASE_URL"]
    return settings


def validate_settings(settings, parser):
    for key in ("attempts", "workers", "clients", "limit_race_requests", "per_user_limit",
                "price_paise", "timeout_seconds"):
        if not isinstance(settings[key], int) or isinstance(settings[key], bool) or settings[key] < 1:
            parser.error(f"{key.replace('_', '-')} must be a positive integer")
    if (not isinstance(settings["ramp_up_seconds"], (int, float))
            or isinstance(settings["ramp_up_seconds"], bool) or settings["ramp_up_seconds"] < 0):
        parser.error("ramp-up-seconds must be zero or greater")
    if settings["scenario"] not in ("all", "hot-seat", "idempotency", "user-limit"):
        parser.error("scenario must be all, hot-seat, idempotency, or user-limit")
    if not isinstance(settings["keep_users"], bool):
        parser.error("keep-users must be a boolean")
    if not isinstance(settings["base_url"], str) or not settings["base_url"].startswith(("http://", "https://")):
        parser.error("base-url must be an http:// or https:// URL")
    if not isinstance(settings["user_prefix"], str) or not settings["user_prefix"].strip():
        parser.error("user-prefix cannot be empty")
    if not isinstance(settings["user_domain"], str) or not settings["user_domain"].strip():
        parser.error("user-domain cannot be empty")


def print_plan(settings, dry_run):
    print("\nBurst plan (all configuration is resolved before network requests):")
    print(json.dumps(settings, indent=2))
    stages = {
        "all": ["hot-seat contention", "idempotency replay", "per-user limit race", "metrics"],
        "hot-seat": ["hot-seat contention", "metrics"],
        "idempotency": ["idempotency replay", "metrics"],
        "user-limit": ["per-user limit race", "metrics"],
    }
    print("Stages: " + ", ".join(stages[settings["scenario"]]))
    if settings["scenario"] in ("all", "hot-seat"):
        print(f"Planned hot-seat load: {settings['attempts']} requests, {settings['workers']} workers, "
              f"{settings['clients']} clients, ramp-up={settings['ramp_up_seconds']}s")
    if settings["scenario"] in ("all", "user-limit"):
        print(f"Planned user-limit race: {settings['limit_race_requests']} distinct seats; "
              f"at most {max(0, settings['per_user_limit'] - 1)} expected confirmations after the seed reservation")
    account_count = settings["clients"] if settings["scenario"] in ("all", "hot-seat") else 1
    print(f"Temporary buyer accounts to create: {account_count}; "
          f"cleanup={'disabled' if settings['keep_users'] else 'enabled'}")
    if dry_run:
        print("Dry run: no HTTP requests were sent.")


def outcome_label(result):
    if result["status"] == 0:
        return "transport_error"
    if result["status"] >= 500:
        return f"5xx_{result['status']}"
    if result["status"] == 201:
        return "confirmed"
    return result.get("code") or f"http_{result['status']}"


def print_results(title, results, wall_seconds):
    counts = Counter(outcome_label(result) for result in results)
    print(f"\n{title}: {len(results)} requests")
    for outcome, count in sorted(counts.items()):
        print(f"  {outcome}: {count}")
    latencies = [result["elapsed"] for result in results]
    if latencies:
        ordered = sorted(latencies)
        p95 = ordered[min(len(ordered) - 1, int(len(ordered) * 0.95))]
        print(f"  latency_ms: p50={statistics.median(ordered) * 1000:.1f}, p95={p95 * 1000:.1f}, max={max(ordered) * 1000:.1f}")
        print(f"  throughput: {len(results) / max(wall_seconds, 0.001):.1f} requests/s over {wall_seconds:.2f}s")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", help="JSON file with workload defaults; CLI options override it")
    parser.add_argument("--dry-run", action="store_true", help="print resolved plan without sending requests")
    parser.add_argument("--base-url")
    parser.add_argument("--attempts", type=int, help="hot-seat request count")
    parser.add_argument("--workers", type=int, help="maximum concurrent HTTP workers")
    parser.add_argument("--clients", type=int, help="temporary buyer accounts rotated through the hot-seat storm")
    parser.add_argument("--ramp-up-seconds", type=float, help="spread hot-seat request starts over this duration")
    parser.add_argument("--limit-race-requests", type=int, help="distinct seats in the per-user limit race")
    parser.add_argument("--per-user-limit", type=int, help="per-user limit used when creating test shows")
    parser.add_argument("--price-paise", type=int, help="ticket price used for temporary shows")
    parser.add_argument("--timeout-seconds", type=int, help="HTTP timeout per request")
    parser.add_argument("--scenario", choices=("all", "hot-seat", "idempotency", "user-limit"),
                        help="which workload stages to run")
    cleanup = parser.add_mutually_exclusive_group()
    cleanup.add_argument("--keep-users", dest="keep_users", action="store_true", default=None,
                         help="keep temporary accounts instead of deleting them after the run")
    cleanup.add_argument("--delete-users", dest="keep_users", action="store_false", default=None,
                         help="delete temporary accounts after the run")
    parser.add_argument("--user-prefix", help="prefix for generated test-user emails")
    parser.add_argument("--user-domain", help="domain for generated test-user emails")
    parser.add_argument("--admin", default=os.getenv("ADMIN_IDENTIFIER", "admin"))
    parser.add_argument("--admin-password", default=os.getenv("ADMIN_PASSWORD", "admin123"))
    parser.add_argument("--user-password", default=os.getenv("BURST_USER_PASSWORD", "BurstTestPass123!"))
    args = parser.parse_args()
    settings = load_settings(args, parser)
    validate_settings(settings, parser)
    settings["user_prefix"] = settings["user_prefix"].strip()
    settings["user_domain"] = settings["user_domain"].strip().lstrip("@").lower()
    base_url = settings["base_url"].rstrip("/")
    settings["base_url"] = base_url
    print_plan(settings, args.dry_run)
    if args.dry_run:
        return
    timeout = settings["timeout_seconds"]
    readiness = request(base_url, "GET", "/actuator/health/readiness", timeout=timeout)
    require_success(readiness, 200, "application readiness")
    admin_token = sign_in(base_url, args.admin, args.admin_password, settings["timeout_seconds"])
    run_id = uuid.uuid4().hex
    users = []
    started_at = time.perf_counter()
    hot_results = []
    limit_results = []
    replay_same_id = True
    run_hot = settings["scenario"] in ("all", "hot-seat")
    run_idempotency = settings["scenario"] in ("all", "idempotency")
    run_limit = settings["scenario"] in ("all", "user-limit")
    account_count = settings["clients"] if run_hot else 1
    hot_show = None
    limit_show = None
    try:
        for index in range(account_count):
            email = f"{settings['user_prefix']}-{run_id}-{index}@{settings['user_domain']}"
            created = request(base_url, "POST", "/users",
                              body={"email": email, "password": args.user_password},
                              timeout=settings["timeout_seconds"])
            user = require_success(created, 201, f"create burst user {index}")
            users.append(user["id"])
        tokens = [sign_in(base_url, f"{settings['user_prefix']}-{run_id}-{index}@{settings['user_domain']}",
                          args.user_password, settings["timeout_seconds"])
                  for index in range(account_count)]

        if run_hot:
            hot_show = create_show(base_url, admin_token, f"burst-hot-{run_id}", ["HOT"], 1,
                                   settings["price_paise"], timeout)
        if run_idempotency or run_limit:
            seat_count = settings["limit_race_requests"] + 1 if run_limit else 1
            limit_show = create_show(base_url, admin_token, f"burst-limit-{run_id}",
                                     [f"L{index}" for index in range(1, seat_count + 1)],
                                     settings["per_user_limit"], settings["price_paise"], timeout)

        print(f"Base URL: {base_url}")
        if run_hot:
            print(f"Hot-seat run: attempts={settings['attempts']}, workers={settings['workers']}, "
                  f"clients={settings['clients']}, ramp_up_seconds={settings['ramp_up_seconds']}")
            hot_started = time.perf_counter()
            with concurrent.futures.ThreadPoolExecutor(max_workers=settings["workers"]) as executor:
                scheduled_at = time.perf_counter()
                futures = [executor.submit(reserve_after, scheduled_at,
                                           index * settings["ramp_up_seconds"] / max(settings["attempts"] - 1, 1),
                                           base_url, hot_show, tokens[index % len(tokens)],
                                           f"hot-{run_id}-{index}", ["HOT"], timeout)
                           for index in range(settings["attempts"])]
                hot_results = [future.result() for future in concurrent.futures.as_completed(futures)]
            print_results("Hot-seat storm", hot_results, time.perf_counter() - hot_started)

        token = tokens[0]
        if run_idempotency:
            first = reserve(base_url, limit_show, token, f"retry-{run_id}", ["L1"], timeout)
            replay = reserve(base_url, limit_show, token, f"retry-{run_id}", ["L1"], timeout)
            require_success(first, 201, "initial idempotent reservation")
            require_success(replay, 201, "same-key replay")
            replay_same_id = first["body"].get("reservation_id") == replay["body"].get("reservation_id")
            print(f"\nIdempotency retry: initial={first['status']}, replay={replay['status']}, same_reservation={replay_same_id}")

        if run_limit:
            if not run_idempotency:
                seed = reserve(base_url, limit_show, token, f"seed-{run_id}", ["L1"], timeout)
                require_success(seed, 201, "seed reservation for per-user limit race")
            limit_started = time.perf_counter()
            with concurrent.futures.ThreadPoolExecutor(max_workers=min(settings["workers"], settings["limit_race_requests"])) as executor:
                limit_results = list(executor.map(
                    lambda label: reserve(base_url, limit_show, token, f"limit-{run_id}-{label}",
                                          [label], timeout),
                    [f"L{index}" for index in range(2, settings["limit_race_requests"] + 2)]))
            print_results(f"Per-user limit race ({settings['limit_race_requests']} distinct seats after one existing reservation)",
                          limit_results, time.perf_counter() - limit_started)

        reconciled = True
        shows_to_check = []
        if hot_show:
            shows_to_check.append(("hot", hot_show))
        if limit_show:
            shows_to_check.append(("limit", limit_show))
        for label, show_id in shows_to_check:
            state = show_state(base_url, show_id, timeout)
            counts = state["counts"]
            total = state["total_seats"]
            reconciled = counts["available"] + counts["held"] + counts["confirmed"] == total
            print(f"{label} inventory: available={counts['available']} held={counts['held']} "
                  f"confirmed={counts['confirmed']} total={total} reconciled={reconciled}")
            if not reconciled:
                raise RuntimeError(f"{label} inventory does not reconcile")

        metrics = request(base_url, "GET", "/actuator/prometheus",
                          accept="text/plain;version=0.0.4;charset=utf-8", timeout=timeout)
        if metrics["status"] == 200:
            lines = metrics["body"].get("raw", "").splitlines()
            selected = [line for line in lines if line.startswith((
                "seat_reservation_confirmed_total", "seat_reservation_declined_total",
                "seat_reservation_idempotent_replays_total", "seat_reservation_cancellations_total",
                "seat_reservation_seats_available"))]
            print("\nReservation metrics:")
            for line in selected:
                print(f"  {line}")
        else:
            print(f"\nMetrics endpoint returned HTTP {metrics['status']}: "
                  f"{metrics.get('body', metrics.get('error'))}")

        tested_results = hot_results + limit_results
        errors = sum(result["status"] == 0 or result["status"] >= 500 for result in tested_results)
        hot_confirmed = sum(result["status"] == 201 for result in hot_results)
        hot_conflicts = sum(result["status"] == 409 and result.get("code") == "SEAT_UNAVAILABLE"
                            for result in hot_results)
        limit_confirmed = sum(result["status"] == 201 for result in limit_results)
        limit_declines = sum(result["status"] == 409 and result.get("code") == "USER_SEAT_LIMIT_EXCEEDED"
                             for result in limit_results)
        print(f"\nSummary: elapsed_seconds={time.perf_counter() - started_at:.1f}, "
              f"hot_seat_confirmed={hot_confirmed}, hot_seat_conflicts={hot_conflicts}, "
              f"limit_confirmed={limit_confirmed}, limit_declines={limit_declines}, "
              f"server_or_transport_errors={errors}, idempotency_same_id={replay_same_id}")
        failed = errors > 0 or not reconciled or metrics["status"] != 200
        if run_hot:
            failed = failed or hot_confirmed != 1 or hot_conflicts != settings["attempts"] - 1
        if run_limit:
            failed = failed or limit_confirmed != min(settings["limit_race_requests"], settings["per_user_limit"] - 1)
            failed = failed or limit_declines != settings["limit_race_requests"] - limit_confirmed
        if run_idempotency:
            failed = failed or not replay_same_id
        if failed:
            raise SystemExit("Burst verification failed; inspect the response and inventory summaries above")
    finally:
        for user_id in ([] if settings["keep_users"] else users):
            try:
                request(base_url, "DELETE", f"/users/{user_id}", token=admin_token,
                        timeout=timeout)
            except Exception:
                pass


if __name__ == "__main__":
    main()
