"""Export separate-client audio observations and draw the late-listener results.

Pass completed attempt directories to regenerate data; omit them to plot the
committed observations. Never counts failed or diagnostic runs as measurements.
"""
import argparse
import json
from pathlib import Path
import statistics
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('attempts', nargs='*', type=Path)
parser.add_argument('--output', type=Path, default=ROOT / 'docs/benchmarks/late-listeners')
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
data_path = args.output / 'observations.json'
if args.attempts:
    data = {'scope': 'Separate vanilla audio engines on one Windows host, loopback networking. Native audio-clock observations, not acoustic measurements or Internet latency guarantees.', 'attempts': []}
    for directory in args.attempts:
        result = json.loads((directory / 'result.json').read_text())
        if result['status'] != 'complete' or result.get('functionalOnly'):
            raise ValueError('Only complete normal-JIT runs can be plotted: ' + str(directory))
        origin = next(p['hostNanos'] for p in result['checkpoints'] if p['name'] in ('insert-disc', 'insert-cat'))
        result['checkpoints'] = [{'name': p['name'], 'seconds': (p['hostNanos'] - origin) / 1e9} for p in result['checkpoints']]
        result['attempt'] = directory.name
        result['clients'] = {}
        for role in ('a', 'b'):
            sibling = directory.with_name(directory.name + '-' + role)
            report = json.loads((sibling / 'observe.json').read_text())
            launch = json.loads((sibling / 'launch.json').read_text())
            assert report['status'] == launch['status'] == 'complete'
            samples = []
            for frame in report['observations']:
                for sound in frame['sounds']:
                    if abs(sound['x'] - .5) > .01 or abs(sound['z'] - .5) > .01:
                        continue
                    samples.append({'wallSeconds': (frame['hostNanos'] - origin) / 1e9,
                        'songSeconds': (frame['hostNanos'] - sound['mediaEpochNanos']) / 1e9,
                        'epochSeconds': (sound['mediaEpochNanos'] - origin) / 1e9,
                        'nativeStartSeconds': sound['nativeStartSeconds'], 'source': sound['source'],
                        'buffer': sound['buffer'], 'event': sound['event'], 'pcmFrames': sound['pcmFrames']})
            result['clients'][role] = {'java': report['java'], 'runtimeFlags': launch['runtimeJvmFlags'], 'samples': samples}
        data['attempts'].append(result)
    data_path.write_text(json.dumps(data, indent=2) + '\n')
else:
    data = json.loads(data_path.read_text())

plt.rcParams.update({'font.size': 10, 'axes.spines.top': False, 'axes.spines.right': False})
fig, axes = plt.subplots(1, 2, figsize=(12, 4.5), gridspec_kw={'width_ratios': [1.7, 1]})
case = data['attempts'][0]
for role, label, color in [('a', 'Original listener', '#b66c25'), ('b', 'Late listener', '#207a83')]:
    samples = case['clients'][role]['samples']
    events = dict.fromkeys(s['event'] for s in samples)
    for index, event in enumerate(events):
        group = [s for s in samples if s['event'] == event]
        axes[0].plot([s['wallSeconds'] for s in group], [s['songSeconds'] for s in group],
                     linewidth=2.5 if role == 'a' else 1.7,
                     linestyle='-' if role == 'a' else '--',
                     label=label if index == 0 else None, color=color)
axes[0].set(xlabel='Seconds after disc insertion', ylabel='Observed song position (seconds)',
            title='Late listener joins the running timeline')
for point in case['checkpoints']:
    if point['name'] == 'late-client-connect':
        axes[0].axvline(point['seconds'], color='#777777', linestyle=':', linewidth=1)
        axes[0].text(point['seconds'] + .6, 2, 'Late login', rotation=90, color='#666666')
axes[0].legend(frameon=False)
values = [abs(a['alignmentErrorMs']) for a in data['attempts']]
labels = [a.get('sound', 'minecraft:music_disc.cat').split(':')[-1].replace('music_disc.', '') + '\n' + a['attempt'] for a in data['attempts']]
axes[1].bar(range(len(values)), values, color='#207a83', width=.55)
axes[1].set_xticks(range(len(values)), labels, fontsize=8)
axes[1].set(ylabel='Absolute difference (ms)', title='Median timeline difference between clients', ylim=(0, max(100, max(values) * 1.3)))
for i, value in enumerate(values):
    axes[1].text(i, value + 2, f'{value:.1f} ms', ha='center')
for axis in axes:
    axis.grid(axis='y', alpha=.2)
    axis.set_axisbelow(True)
fig.suptitle('Copper Speakers 0.2.0 · server-only late listeners', fontweight='bold')
fig.text(.5, .01, 'Two independent clients on one host; normal JIT, 4 processors each, stack pre-touch enabled. No client playback implementation.', ha='center', fontsize=8)
fig.tight_layout(rect=(0, .035, 1, .96))
fig.savefig(args.output / 'joining.png', dpi=180)
fig.savefig(args.output / 'joining.svg')
svg = args.output / 'joining.svg'
svg.write_text('\n'.join(line.rstrip() for line in svg.read_text().splitlines()) + '\n')
plt.close(fig)
print('Wrote', data_path, 'and joining.png / joining.svg')
