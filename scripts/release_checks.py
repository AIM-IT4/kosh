#!/usr/bin/env python3
"""Fail-closed APK verification and device evidence tied to immutable build artifacts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from datetime import datetime

SIGNER = '02283b8d09a5a1912906e9b5d3abed25ed4445de005f57a79cea0443f0317934'
EDITIONS = {'standard': ('Kosh.apk', 'com.amitjha.kosh'),
            'offline': ('Kosh-offline.apk', 'com.amitjha.kosh.offline')}
CHECKS = {'fresh_scan', 'denial_recovery', 'return_without_grant', 'manual_entry',
          'restricted_installer', 'split_permissions', 'live_import', 'missed_sms_catch_up',
          'empty_inbox_catch_up', 'no_duplicates', 'revoke_regrant', 'upgrade_data_preserved'}
CONDITIONAL = {'restricted_installer', 'split_permissions'}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def run(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout


def build_tools():
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    require(sdk, 'Set ANDROID_HOME or ANDROID_SDK_ROOT')
    versions = [p for p in (Path(sdk) / 'build-tools').iterdir()
                if re.fullmatch(r'\d+(\.\d+)+', p.name)]
    require(versions, 'No stable Android build-tools installed')
    return max(versions, key=lambda p: tuple(map(int, p.name.split('.'))))


def offline_permissions(apk, tool_dir=None):
    # Inspection MUST succeed before absence of INTERNET can mean anything.
    text = run(str((tool_dir or build_tools()) / 'aapt2'), 'dump', 'permissions', str(apk))
    names = set(re.findall(r"name='([^']+)'", text))
    require({'android.permission.READ_SMS', 'android.permission.RECEIVE_SMS'} <= names,
            'APK is missing expected SMS permissions (or permission output is invalid)')
    require('android.permission.INTERNET' not in names, 'Offline APK requests INTERNET')


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def verify_apks(directory, source_sha, run_id):
    require(re.fullmatch(r'[0-9a-f]{40}', source_sha), 'Source must be a full commit SHA')
    require(str(run_id).isdigit() and int(run_id) > 0, 'A candidate workflow run ID is required')
    gradle = run('git', 'show', f'{source_sha}:app/build.gradle.kts')
    version = re.search(r'versionName = "([^"]+)"', gradle).group(1)
    code = int(re.search(r'versionCode = (\d+)', gradle).group(1))
    tool_dir = build_tools()
    manifest = {'source_sha': source_sha, 'build_run_id': str(run_id), 'version_name': version,
                'version_code': code, 'apks': {}}
    for edition, (filename, package) in EDITIONS.items():
        apk = Path(directory) / filename
        certs = run(str(tool_dir / 'apksigner'), 'verify', '--print-certs', str(apk))
        signers = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)', certs)
        require(signers == [SIGNER], f'{filename}: unexpected signing certificate')
        badging = run(str(tool_dir / 'aapt2'), 'dump', 'badging', str(apk))
        info = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
        require(info and info.groups() == (package, str(code), version), f'{filename}: wrong package/version')
        manifest['apks'][edition] = {'file': filename, 'application_id': package,
                                    'sha256': sha256(apk), 'signer': SIGNER}
    offline_permissions(Path(directory) / 'Kosh-offline.apk', tool_dir)
    return manifest


def validate_evidence(manifest, evidence):
    for key in ('source_sha', 'build_run_id'):
        require(evidence.get(key) == manifest[key], f'Device evidence does not match {key}')
    require(set(evidence.get('apks', {})) == set(EDITIONS), 'Evidence must cover both editions')
    for edition in EDITIONS:
        record = evidence['apks'][edition]
        require(record.get('sha256') == manifest['apks'][edition]['sha256'], f'{edition}: APK hash mismatch')
        records = record.get('runs', [])
        apis = []
        for case in records:
            require(case.get('physical') is True, f'{edition}: physical-device evidence required')
            for key in ('device', 'installer', 'tested_by'):
                require(isinstance(case.get(key), str) and case[key].strip(), f'{edition}: missing {key}')
            datetime.fromisoformat(case['tested_at'])
            api = case.get('android_api')
            require(type(api) is int and api >= 26, 'Invalid Android API')
            apis.append(api)
            old_code = case.get('upgrade_from_version_code')
            require(type(old_code) is int and 0 < old_code < manifest['version_code'],
                    f'{edition}: record the older installed version code used for upgrade')
            results = case.get('checks', {})
            require(set(results) == CHECKS, f'{edition}: incomplete device checklist')
            for name, status in results.items():
                if name in CONDITIONAL and status == 'not_available':
                    reason = case.get('exceptions', {}).get(name, '')
                    require(isinstance(reason, str) and reason.strip(), f'{edition}: explain unavailable {name}')
                else:
                    require(status == 'pass', f'{edition}: {name} has not passed')
        require(26 in apis and any(api >= 35 for api in apis),
                f'{edition}: cover physical API 26 and API 35+ devices')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    off = sub.add_parser('offline')
    off.add_argument('apk')
    apk = sub.add_parser('apks')
    apk.add_argument('directory', type=Path)
    apk.add_argument('--source', required=True)
    apk.add_argument('--run-id', required=True)
    apk.add_argument('--write', action='store_true')
    ev = sub.add_parser('evidence')
    ev.add_argument('directory', type=Path)
    ev.add_argument('record', type=Path)
    args = parser.parse_args()
    if args.command == 'offline':
        offline_permissions(args.apk)
    elif args.command == 'apks':
        manifest = verify_apks(args.directory, args.source, args.run_id)
        path = args.directory / 'candidate.json'
        if args.write:
            path.write_text(json.dumps(manifest, indent=2) + '\n')
            (args.directory / 'SHA256SUMS').write_text(''.join(
                f"{item['sha256']}  {item['file']}\n" for item in manifest['apks'].values()))
        else:
            require(json.loads(path.read_text()) == manifest, 'Artifact manifest differs from verified APKs/source')
    else:
        manifest = json.loads((args.directory / 'candidate.json').read_text())
        for item in manifest['apks'].values():
            require(sha256(args.directory / item['file']) == item['sha256'], 'APK changed since build')
        validate_evidence(manifest, json.loads(args.record.read_text()))
    print('Verification passed')


if __name__ == '__main__':
    main()
