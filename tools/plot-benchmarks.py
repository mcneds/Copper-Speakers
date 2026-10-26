"""Export completed current-mod runs and regenerate public benchmark figures.

Requires matplotlib. With --suite, copy measurements from local validation output,
removing screenshot paths. Without it, plot the committed portable dataset.
"""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import statistics

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D

ROOT = Path(__file__).resolve().parents[1]
COLORS = {'cold': '#b65d32', 'cached': '#117d79'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite', type=Path)
    parser.add_argument('--data', type=Path, default=ROOT / 'docs/benchmarks/data/0.1.9.json')
    parser.add_argument('--output', type=Path, default=ROOT / 'docs/benchmarks')
    args = parser.parse_args()
    if args.suite:
        suite = json.loads(args.suite.read_text())
        runs = []
        for attempt in suite['attempts']:
            if not attempt['included']:
                continue
            directory = args.suite.parent.parent / attempt['name']
            raw = directory / 'audio.json'
            report = json.loads(raw.read_text())
            launch = json.loads((directory / 'launch.json').read_text())
            assert report['status'] == launch['status'] == 'complete'
            assert not launch['diagnosticFlags']
            report.pop('screenshot', None)
            runs.append({'attempt': attempt['name'], 'mode': attempt['mode'],
                         'originalReportSha256': hashlib.sha256(raw.read_bytes()).hexdigest(),
                         'launch': {k: v for k, v in launch.items() if k not in ('pid',)},
                         'measurement': report})
        data = {'suite': suite, 'runs': runs}
        args.data.parent.mkdir(parents=True, exist_ok=True)
        args.data.write_text(json.dumps(data, indent=2) + '\n')
    else:
        data = json.loads(args.data.read_text())
    assert data['suite']['status'] == 'complete', 'Do not present a partial suite as complete'
    version = {mod.split('=', 1)[1] for run in data['runs'] for mod in run['measurement']['mods']
               if mod.startswith('copper_speakers=')}
    assert len(version) == 1
    version = version.pop()
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'data').mkdir(parents=True, exist_ok=True)
    plt.rcParams.update({'font.family': 'DejaVu Sans', 'font.size': 11, 'axes.spines.top': False,
                         'axes.spines.right': False, 'axes.edgecolor': '#b5bfc5',
                         'text.color': '#24343d', 'axes.labelcolor': '#24343d',
                         'xtick.color': '#425660', 'ytick.color': '#425660',
                         'svg.hashsalt': 'copper-speakers-benchmark', 'savefig.facecolor': 'white'})
    counts = data['suite']['networkSources']
    rounds = data['suite']['rounds']
    summary = []
    for run in data['runs']:
        m = run['measurement']
        assert len(m['trials']) == 2 * rounds
        for trial in m['trials']:
            for phase in ('initial', 'later'):
                samples = trial[phase]
                assert len(samples) == m['networkSources'] + m['reservedSources']
                assert all(s['state'] == 4114 and s['buffer'] != 0 for s in samples)
                assert len({s['buffer'] for s in samples}) == 1
                measured = 1000 * (max(s['start'] for s in samples) - min(s['start'] for s in samples))
                stored = trial['spreadMs' if phase == 'initial' else 'laterSpreadMs']
                assert abs(measured - stored) < 1e-6
        for cache in ('cold', 'cached'):
            trials = [t for t in m['trials'] if t['mode'] == cache]
            assert len(trials) == rounds
            summary.append({'song': 'Cat' if run['mode'] == 'smoke' else 'Synthetic custom disc',
                            'networkSources': m['networkSources'], 'totalVoices': m['networkSources'] + 4,
                            'cache': cache, 'trials': len(trials),
                            'medianStartSpreadMs': statistics.median(t['spreadMs'] for t in trials),
                            'maxStartSpreadMs': max(t['spreadMs'] for t in trials),
                            'maxLaterSpreadMs': max(t['laterSpreadMs'] for t in trials),
                            'maxRelativeDriftMs': max(t['relativeDriftMs'] for t in trials),
                            'medianObservedReadyMs': statistics.median(t['observedReadyMs'] for t in trials),
                            'maxObservedReadyMs': max(t['observedReadyMs'] for t in trials),
                            'pcmBytes': trials[0]['initial'][0]['pcmBytes'],
                            'minObservationSeconds': min(t['observationSeconds'] for t in trials),
                            'maxObservationSeconds': max(t['observationSeconds'] for t in trials)})
    with (args.output / 'data/summary.csv').open('w', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(summary[0]))
        writer.writeheader(); writer.writerows(summary)

    def save(fig, name):
        fig.savefig(args.output / (name + '.png'), dpi=160)
        fig.savefig(args.output / (name + '.svg'), metadata={'Date': None})
        svg = args.output / (name + '.svg')
        svg.write_text('\n'.join(line.rstrip() for line in svg.read_text().splitlines()) + '\n')
        plt.close(fig)

    for metric, title, ylabel, footnote, filename in (
        ('spreadMs', 'How closely do speakers start together?', 'Measured start spread (ms)',
         'Clock-paired native observations; includes four reserved voices. Lower is closer. No acoustic capture.', 'timing'),
        ('observedReadyMs', 'How long until every voice is observed playing?', 'Observed ready time (ms)',
         'Local insertion to all voices observed playing. Includes server, network and polling; upper bound, not output latency.', 'startup'),
    ):
        fig, axes = plt.subplots(1, 2, figsize=(11, 5.4), sharey=True)
        for ax, kind, label in zip(axes, ('smoke', 'custom'), ('Vanilla Cat disc', '60-second synthetic custom disc')):
            selected = [r['measurement'] for r in data['runs'] if r['mode'] == kind]
            for i, count in enumerate(counts):
                m = next(m for m in selected if m['networkSources'] == count)
                for cache, offset in (('cold', -.15), ('cached', .15)):
                    values = [t[metric] for t in m['trials'] if t['mode'] == cache]
                    x = i + offset
                    jitter = [(j - (len(values) - 1) / 2) * .012 for j in range(len(values))]
                    ax.scatter([x + v for v in jitter], values, s=30, color=COLORS[cache], alpha=.65, zorder=3)
                    ax.plot([x - .09, x + .09], [statistics.median(values)] * 2, color=COLORS[cache], lw=3, zorder=4)
            ax.set_title(label, loc='left', fontsize=12, pad=14)
            ax.set_xticks(range(len(counts)), [f'{n}\n({n+4} voices)' for n in counts])
            ax.set_xlim(-.55, len(counts)-.45)
            ax.set_xlabel('Sound positions: jukebox + speakers', labelpad=10)
            ax.grid(axis='y', color='#e6ebed'); ax.set_axisbelow(True)
        # Give zero-spread measurements a little room while retaining a zero-labelled baseline.
        upper = max(t[metric] for r in data['runs'] for t in r['measurement']['trials'])
        axes[0].set_ylim(-max(1, upper) * .045, max(1, upper) * 1.12)
        axes[0].set_ylabel(ylabel)
        fig.suptitle(f'Copper Speakers {version}  |  {title}', x=.07, ha='left', fontsize=15, fontweight='bold')
        fig.legend([Line2D([], [], color=COLORS[c], marker='o', lw=2) for c in COLORS],
                   ['Cold decoded cache', 'Cached replay'], loc='upper right', bbox_to_anchor=(.97,.91), frameon=False, ncol=2)
        fig.text(.07, .065, f'Each dot is one playback; bars mark medians. {rounds} trials per cache state and configuration.', fontsize=9)
        fig.text(.07, .025, footnote, fontsize=8.5)
        fig.subplots_adjust(top=.78, bottom=.25, left=.08, right=.97, wspace=.12)
        save(fig, filename)

    fig, ax = plt.subplots(figsize=(11, 5.2))
    for kind, label, color in (('smoke', 'Vanilla Cat disc', '#b65d32'), ('custom', '60-second synthetic custom disc', '#117d79')):
        values = []
        for count in counts:
            m = next(r['measurement'] for r in data['runs'] if r['mode'] == kind and r['measurement']['networkSources'] == count)
            sizes = {t['initial'][0]['pcmBytes'] for t in m['trials']}
            assert len(sizes) == 1
            values.append(sizes.pop() / 1024**2)
        ax.plot(range(len(counts)), values, marker='o', lw=2, color=color, label=label)
        for i, value in enumerate(values):
            ax.annotate(f'{value:.2f} MiB', (i,value), xytext=(0,9), textcoords='offset points', ha='center', color=color)
    ax.set_xticks(range(len(counts)), [f'{n} positions / {n+4} voices' for n in counts])
    ax.set_xlim(-.4, len(counts)-.6); ax.set_ylim(bottom=0, top=ax.get_ylim()[1]*1.15)
    ax.set_ylabel('One shared native PCM buffer (MiB)')
    ax.set_xlabel('Sound positions: jukebox + speakers', labelpad=12)
    ax.grid(axis='y', color='#e6ebed'); ax.set_axisbelow(True)
    ax.legend(frameon=False, loc='center right')
    fig.suptitle(f'Copper Speakers {version}  |  More speakers share the same decoded audio', x=.08, ha='left', fontsize=15, fontweight='bold')
    fig.text(.08,.055,'Every observed voice used the same nonzero OpenAL buffer in all measured playbacks.',fontsize=9)
    fig.text(.08,.02,'Audio payload only: excludes decoder allocations, other cached songs, mixer state and total process memory.',fontsize=9)
    fig.subplots_adjust(top=.84,bottom=.24,left=.09,right=.97)
    save(fig, 'memory')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
