"""Local per-song prepared-joining control. Does not publish ordinary music or contact a server."""
import argparse
import json
import os
import re
from pathlib import Path
import tempfile
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True, help='Server config/copper-speakers/joining directory')
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('status')
    for command in ('enable', 'disable', 'delete'):
        action = sub.add_parser(command)
        action.add_argument('sound', help='Original sound-event identifier, not jukebox song ID')
        if command == 'enable':
            action.add_argument('--source', type=Path, help='Published mono Vorbis file; optional for legacy catalogues')
            action.add_argument('--spacing', type=float, default=15)
    args = parser.parse_args()
    root = args.root.resolve()
    if args.command == 'status':
        print((root / 'status.json').read_text(encoding='utf-8'))
        return
    if not re.fullmatch(r'[a-z0-9_.-]+:[a-z0-9/._-]+', args.sound):
        parser.error('Use an original sound-event identifier such as example:evening')
    root.mkdir(parents=True, exist_ok=True)
    file = root / 'settings.json'
    settings = json.loads(file.read_text(encoding='utf-8')) if file.exists() else {'format': 1, 'tracks': {}}
    if settings.get('format') != 1:
        parser.error('Unsupported settings format')
    if args.command == 'enable' and not 1 <= args.spacing <= 30:
        parser.error('Spacing must be 1..30 seconds')
    row = settings['tracks'].setdefault(args.sound, {})
    row.update(revision=str(uuid.uuid4()), enabled=args.command == 'enable', deletePrepared=args.command == 'delete')
    if args.command == 'enable':
        row['spacingSeconds'] = args.spacing
        if args.source:
            row['source'] = str(args.source.resolve(strict=True))
    # The bridge must serialize writers; replace atomically so the server sees complete revisions.
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', dir=root, prefix='settings-', suffix='.tmp',
                                         encoding='utf-8', delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(settings, stream, indent=2)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        temporary.replace(file)
    finally:
        if temporary and temporary.exists():
            temporary.unlink()
    print(json.dumps({'sound': args.sound, 'revision': row['revision'], 'submitted': True}))


if __name__ == '__main__':
    main()
