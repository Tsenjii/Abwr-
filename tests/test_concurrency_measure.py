import unittest
from bridge.abwr_shim import AbwrError
from scripts.concurrency_measure import measure


class ConcurrencyMeasurementTests(unittest.TestCase):
    def test_busy_reduces_recommendation_without_replaying_any_work(self):
        calls=[]
        def read(profile):
            calls.append(profile)
            if profile=='B':raise AbwrError('BUSY','bounded queue',True)
            return 'fixture'
        report=measure(['A','B'],read,lambda p:{'extensionPortReady':True},lambda:{'pageWorkers':2},rounds=3)
        self.assertEqual(report['maximumReadConcurrencyProven'],1)
        self.assertEqual(report['recommendedWorkers'],1)
        self.assertEqual(calls.count('B'),1)
        self.assertEqual(len(report['levels'][1]['results']),1)
        self.assertIsNone(report['writeConcurrencyProven'])

    def test_lost_bridge_prevents_configuration_being_reported_as_measured_success(self):
        report=measure(['A'],lambda p:'fixture',lambda p:{'extensionPortReady':False},lambda:{'pageWorkers':2},rounds=3)
        self.assertEqual(report['maximumReadConcurrencyProven'],0)


if __name__=='__main__':unittest.main()
