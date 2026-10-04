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
            accept="application/json"):
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
        with urllib.request.urlopen(req, timeout=120) as response:
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


def sign_in(base_url, identifier, password):
    response = request(base_url, "POST", "/auth/signin",
                       body={"identifier": identifier, "password": password})
    return require_success(response, 200, f"sign in {identifier}")["access_token"]


def create_show(base_url, admin_token, name, labels, limit):
    response = request(base_url, "POST", "/shows", token=admin_token,
                       body={"name": name, "seats": labels, "price_paise": 100,
                             "per_user_limit": limit})
    return require_success(response, 201, f"create show {name}")["id"]


def show_state(base_url, show_id):
    response = request(base_url, "GET", f"/shows/{show_id}")
    return require_success(response, 200, f"read show {show_id}")


def reserve(base_url, show_id, token, key, seats):
    return request(base_url, "POST", f"/shows/{show_id}/reserve", token=token,
                   idempotency_key=key, body={"seats": seats})


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
    parser.add_argument("--base-url", default=os.getenv("BASE_URL", "http://localhost:8080"))
    parser.add_argument("--attempts", type=int, default=20000,
                        help="hot-seat request count (default: 20000)")
    parser.add_argument("--workers", type=int, default=200,
                        help="maximum concurrent HTTP workers (default: 200)")
    parser.add_argument("--clients", type=int, default=200,
                        help="temporary buyer accounts rotated through the hot-seat storm")
    parser.add_argument("--admin", default=os.getenv("ADMIN_IDENTIFIER", "admin"))
    parser.add_argument("--admin-password", default=os.getenv("ADMIN_PASSWORD", "admin123"))
    parser.add_argument("--user-password", default=os.getenv("BURST_USER_PASSWORD", "BurstTestPass123!"))
    args = parser.parse_args()
    if args.attempts < 1 or args.workers < 1 or args.clients < 1:
        parser.error("attempts, workers, and clients must all be positive")

    base_url = args.base_url.rstrip("/")
    readiness = request(base_url, "GET", "/actuator/health/readiness")
    require_success(readiness, 200, "application readiness")
    admin_token = sign_in(base_url, args.admin, args.admin_password)
    run_id = uuid.uuid4().hex
    users = []
    started_at = time.perf_counter()
    hot_results = []
    try:
        for index in range(args.clients):
            email = f"burst-{run_id}-{index}@example.test"
            created = request(base_url, "POST", "/users",
                              body={"email": email, "password": args.user_password})
            user = require_success(created, 201, f"create burst user {index}")
            users.append(user["id"])
        tokens = [sign_in(base_url, f"burst-{run_id}-{index}@example.test", args.user_password)
                  for index in range(args.clients)]

        hot_show = create_show(base_url, admin_token, f"burst-hot-{run_id}", ["HOT"], 1)
        limit_show = create_show(base_url, admin_token, f"burst-limit-{run_id}",
                                 [f"L{index}" for index in range(1, 7)], 2)

        print(f"Base URL: {base_url}")
        print(f"Hot-seat run: attempts={args.attempts}, workers={args.workers}, clients={args.clients}")
        hot_started = time.perf_counter()
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
            futures = [executor.submit(reserve, base_url, hot_show,
                                       tokens[index % len(tokens)], f"hot-{run_id}-{index}", ["HOT"])
                       for index in range(args.attempts)]
            hot_results = [future.result() for future in concurrent.futures.as_completed(futures)]
        print_results("Hot-seat storm", hot_results, time.perf_counter() - hot_started)

        token = tokens[0]
        first = reserve(base_url, limit_show, token, f"retry-{run_id}", ["L1"])
        replay = reserve(base_url, limit_show, token, f"retry-{run_id}", ["L1"])
        require_success(first, 201, "initial idempotent reservation")
        require_success(replay, 201, "same-key replay")
        replay_same_id = first["body"].get("reservation_id") == replay["body"].get("reservation_id")
        print(f"\nIdempotency retry: initial={first['status']}, replay={replay['status']}, same_reservation={replay_same_id}")

        limit_started = time.perf_counter()
        with concurrent.futures.ThreadPoolExecutor(max_workers=5) as executor:
            limit_results = list(executor.map(
                lambda label: reserve(base_url, limit_show, token, f"limit-{run_id}-{label}", [label]),
                [f"L{index}" for index in range(2, 7)]))
        print_results("Per-user limit race (five distinct seats after one existing reservation)",
                      limit_results, time.perf_counter() - limit_started)

        hot_state = show_state(base_url, hot_show)
        limit_state = show_state(base_url, limit_show)
        reconciled = True
        for label, state in (("hot", hot_state), ("limit", limit_state)):
            counts = state["counts"]
            total = state["total_seats"]
            reconciled = counts["available"] + counts["held"] + counts["confirmed"] == total
            print(f"{label} inventory: available={counts['available']} held={counts['held']} "
                  f"confirmed={counts['confirmed']} total={total} reconciled={reconciled}")
            if not reconciled:
                raise RuntimeError(f"{label} inventory does not reconcile")

        metrics = request(base_url, "GET", "/actuator/prometheus",
                          accept="text/plain;version=0.0.4;charset=utf-8")
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

        errors = sum(result["status"] == 0 or result["status"] >= 500 for result in hot_results + limit_results)
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
        if (errors or hot_confirmed != 1 or hot_conflicts != args.attempts - 1
                or limit_confirmed != 1 or limit_declines != 4 or not replay_same_id
                or metrics["status"] != 200):
            raise SystemExit("Burst verification failed; inspect the response and inventory summaries above")
    finally:
        for user_id in users:
            try:
                request(base_url, "DELETE", f"/users/{user_id}", token=admin_token)
            except Exception:
                pass


if __name__ == "__main__":
    main()
