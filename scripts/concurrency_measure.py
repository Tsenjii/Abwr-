"""Measure real read-only bridge work. Configuration counts are never proof."""
from concurrent.futures import ThreadPoolExecutor
import threading
import time


def measure(profiles, read, status, queues, rounds=10):
    if not 1 <= rounds <= 100 or not 1 <= len(profiles) <= 2:
        raise ValueError('Measure one or two owned fixture profiles, 1–100 rounds')
    report = {'workload': 'event_pump_delay_then_dom_read', 'rounds': rounds,
              'maximumReadConcurrencyProven': 0, 'writeConcurrencyProven': None,
              'levels': [], 'capacities': queues(), 'recommendedWorkers': 1}
    for workers in range(1, len(profiles) + 1):
        rows = []
        def work(profile, gate):
            gate.wait(timeout=5)
            started = time.monotonic()
            try:
                result = read(profile)
                current = status(profile)
                return {'profile': profile, 'ok': bool(result),
                        'bridgeReady': current.get('extensionPortReady') is True,
                        'documentId': current.get('documentId'),
                        'residentSessions': current.get('residentSessions'),
                        'milliseconds': round((time.monotonic()-started)*1000, 1)}
            except Exception as error:
                # Never print exception messages: they may contain a URL or data.
                return {'profile': profile, 'ok': False,
                        'code': getattr(error, 'code', type(error).__name__),
                        'retryable': getattr(error, 'retryable', False)}
        with ThreadPoolExecutor(max_workers=workers) as pool:
            for index in range(rounds):
                gate = threading.Barrier(workers + 1)
                pending = [pool.submit(work, p, gate) for p in profiles[:workers]]
                gate.wait(timeout=5)
                sample = queues()
                outcomes = [future.result(timeout=30) for future in pending]
                rows.append({'round': index+1, 'queues': sample, 'outcomes': outcomes})
                if any(not x.get('ok') or not x.get('bridgeReady') for x in outcomes):
                    break
        stable = len(rows) == rounds and all(x.get('ok') and x.get('bridgeReady')
                    for row in rows for x in row['outcomes'])
        report['levels'].append({'workers': workers, 'stable': stable, 'results': rows})
        if stable:
            report['maximumReadConcurrencyProven'] = workers
            report['recommendedWorkers'] = workers
        else:
            report['recommendedWorkers'] = max(1, workers-1)
            break
    return report


def measure_sdk(browser, profiles, rounds=10):
    pages = {p: browser.context(p, exit_bucket='fixture').page(
        browser.request('GET', '/v1/status', profile=p)['activeTab']) for p in profiles}
    def read(profile):
        page = pages[profile]
        page.wait_for_timeout(150)
        return page.command('read', maxChars=500).get('text')
    return measure(profiles, read, lambda p: pages[p].status(), browser.queues, rounds)
