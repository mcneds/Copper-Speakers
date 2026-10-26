"""Plot the committed late-listener feasibility measurements. Requires matplotlib."""
import json
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parents[1]
DIRECTORY = ROOT / 'docs/benchmarks'


def main():
    plt.rcParams['svg.hashsalt'] = 'copper-speakers-section-probe'
    data = json.loads((DIRECTORY / 'data/late-join-sections.json').read_text())
    runs = [r for r in data['attempts'] if r['included']]
    fig, axes = plt.subplots(2, 1, figsize=(10, 6), sharex=True, sharey=True)
    for ax, run in zip(axes, runs):
        for pass_number, label, color in ((0, 'First playback', '#b65d32'), (1, 'Cached replay', '#117d79')):
            all_trials = [t for t in run['measurement']['trials'] if t['pass'] == pass_number]
            for previous, current in zip(all_trials, all_trials[1:]):
                a, b = previous['samples'][0], current['samples'][0]
                assert abs(1000 * (b['start'] - a['start'] - a['seconds']) - current['boundaryGapMs']) < 1e-6
            trials = [t for t in run['measurement']['trials'] if t['pass'] == pass_number and 'boundaryGapMs' in t]
            for trial in trials:
                assert all(s['seconds'] == 2 for s in trial['samples'])
            ax.plot([t['part'] for t in trials], [t['boundaryGapMs'] for t in trials],
                    'o-', ms=5, color=color, label=label)
        ax.axhline(0, color='#83939b', lw=1)
        ax.axvline(6, color='#83939b', ls=':', lw=1)
        ax.set_title(run['scheduler'], loc='left', fontsize=11)
        ax.set_ylabel('Gap / overlap (ms)')
        ax.set_ylim(-60, 60)
        ax.grid(axis='y', alpha=.2)
        ax.spines[['top', 'right']].set_visible(False)
    axes[0].legend(loc='upper left', ncol=2, frameon=False)
    axes[1].set_xticks(range(1,12)); axes[1].set_xlabel('Boundary before section number (zero-based)')
    fig.suptitle('Late-listener feasibility: exact-length clips do not guarantee seamless playback', fontsize=13, x=.09, ha='left')
    fig.text(.09,.065,'Positive values are gaps; negative values are overlaps. A second source starts at section 6 (dotted line).',fontsize=8.5)
    fig.text(.09,.03,'Same-client OpenAL observations on 0.1.9. This is not a multiplayer late-login or acoustic validation.',fontsize=8.5)
    fig.subplots_adjust(top=.87,bottom=.19,left=.1,right=.97,hspace=.35)
    fig.savefig(DIRECTORY / 'late-join-sections.png',dpi=160)
    target = DIRECTORY / 'late-join-sections.svg'
    fig.savefig(target,metadata={'Date':None})
    target.write_text('\n'.join(line.rstrip() for line in target.read_text().splitlines())+'\n')


if __name__ == '__main__':
    main()
