import copy
import importlib.util
import subprocess
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('release_checks', Path(__file__).parents[1] / 'release_checks.py')
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)


class ReleaseChecksTest(unittest.TestCase):
    def setUp(self):
        self.manifest = {'source_sha': 'a' * 40, 'build_run_id': '123', 'version_code': 35,
                         'apks': {e: {'sha256': 'b' * 64} for e in checks.EDITIONS}}
        self.evidence = {'source_sha': 'a' * 40, 'build_run_id': '123', 'apks': {}}
        for edition in checks.EDITIONS:
            self.evidence['apks'][edition] = {'sha256': 'b' * 64, 'runs': [
                {'device': 'Test phone', 'android_api': api, 'installer': 'Browser APK installer',
                 'physical': True, 'tested_by': 'Tester', 'tested_at': '2026-11-01',
                 'upgrade_from_version_code': 34, 'checks': {k: 'pass' for k in checks.CHECKS}}
                for api in (26, 35)]}

    def test_complete_evidence_passes(self):
        checks.validate_evidence(self.manifest, self.evidence)

    def test_stale_or_incomplete_evidence_fails(self):
        mutations = [
            lambda e: e.update(source_sha='c' * 40),
            lambda e: e.update(build_run_id='124'),
            lambda e: e['apks'].pop('offline'),
            lambda e: e['apks']['offline'].update(sha256='c' * 64),
            lambda e: e['apks']['standard']['runs'].pop(),
            lambda e: e['apks']['standard']['runs'][0].update(physical=False),
            lambda e: e['apks']['standard']['runs'][0].update(upgrade_from_version_code=35),
            lambda e: e['apks']['standard']['runs'][0]['checks'].update(empty_inbox_catch_up='pending'),
            lambda e: e['apks']['standard']['runs'][0]['checks'].pop('live_import'),
        ]
        for mutate in mutations:
            evidence = copy.deepcopy(self.evidence)
            mutate(evidence)
            with self.subTest(mutation=mutate), self.assertRaises(ValueError):
                checks.validate_evidence(self.manifest, evidence)

    def test_conditional_checks_require_explanation(self):
        record = self.evidence['apks']['offline']['runs'][0]
        record['checks']['split_permissions'] = 'not_available'
        with self.assertRaises(ValueError):
            checks.validate_evidence(self.manifest, self.evidence)
        record['exceptions'] = {'split_permissions': 'This device grants the SMS group together'}
        checks.validate_evidence(self.manifest, self.evidence)

    def test_release_apks_require_correct_signer_package_and_version(self):
        gradle = 'versionName = "2.6.2"\nversionCode = 35'
        sms = "uses-permission: name='android.permission.READ_SMS'\nuses-permission: name='android.permission.RECEIVE_SMS'"
        for fault in (None, 'signer', 'package', 'version'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as directory:
                for filename, _ in checks.EDITIONS.values():
                    (Path(directory) / filename).write_bytes(b'test APK')
                def output(*args):
                    if args[0] == 'git':
                        return gradle
                    if args[0].endswith('apksigner'):
                        signer = '0' * 64 if fault == 'signer' else checks.SIGNER
                        return f'Signer #1 certificate SHA-256 digest: {signer}'
                    if args[2] == 'permissions':
                        return sms
                    package = 'com.amitjha.kosh.offline' if args[-1].endswith('Kosh-offline.apk') else 'com.amitjha.kosh'
                    if fault == 'package':
                        package = 'wrong.app'
                    code = '34' if fault == 'version' else '35'
                    return f"package: name='{package}' versionCode='{code}' versionName='2.6.2'"
                with patch.object(checks, 'build_tools', return_value=Path('/tools')), patch.object(checks, 'run', side_effect=output):
                    if fault:
                        with self.assertRaises(ValueError):
                            checks.verify_apks(directory, 'a' * 40, '123')
                    else:
                        manifest = checks.verify_apks(directory, 'a' * 40, '123')
                        self.assertEqual(manifest['version_code'], 35)
                        self.assertEqual(set(manifest['apks']), {'standard', 'offline'})

    def test_failed_permission_inspection_is_not_a_pass(self):
        with patch.object(checks, 'run', side_effect=subprocess.CalledProcessError(1, 'aapt2')):
            with self.assertRaises(subprocess.CalledProcessError):
                checks.offline_permissions('test.apk', Path('/tools'))

    def test_internet_or_invalid_permission_output_fails(self):
        sms = "uses-permission: name='android.permission.READ_SMS'\nuses-permission: name='android.permission.RECEIVE_SMS'"
        for output in ('', sms + "\nuses-permission: name='android.permission.INTERNET'"):
            with patch.object(checks, 'run', return_value=output), self.assertRaises(ValueError):
                checks.offline_permissions('test.apk', Path('/tools'))
        with patch.object(checks, 'run', return_value=sms):
            checks.offline_permissions('test.apk', Path('/tools'))


if __name__ == '__main__':
    unittest.main()
