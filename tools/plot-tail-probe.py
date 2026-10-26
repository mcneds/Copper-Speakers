"""Plot the committed, explicitly scoped prepared-tail observations."""
import json
from pathlib import Path
import matplotlib.pyplot as plt

plt.rcParams['svg.hashsalt'] = 'copper-speakers-tail-probe'

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'docs/benchmarks/data/late-join-tails.json'


def main():
    data = json.loads(DATA.read_text())
    measured = [a for a in data['attempts'] if a.get('report', {}).get('status') == 'complete'
                and not a['report']['functionalOnly']]
    if len(measured) != 1:
        raise ValueError('This figure expects exactly one completed timing run')
    trials = measured[0]['report']['trials']
    first, second = [], []
    for trial in trials:
        for stage, offset, key, values in (
                ('afterFirstJoin', 6, 'firstJoinErrorMs', first),
                ('afterSecondJoin', 12, 'secondJoinErrorMs', second)):
            samples = trial[stage]
            computed = (samples[-1]['start'] - offset - samples[0]['start']) * 1000
            if abs(computed - trial[key]) > 1e-6:
                raise ValueError('Stored alignment error differs from native observations')
            values.append(computed)
    fig, ax = plt.subplots(figsize=(8, 4.8), layout='constrained')
    x = list(range(len(trials)))
    ax.scatter([v - .08 for v in x], first, s=80, label='Tail begins at song offset 6 s', color='#2563eb')
    ax.scatter([v + .08 for v in x], second, s=80, marker='s', label='Tail begins at song offset 12 s', color='#d97706')
    ax.axhline(0, color='#64748b', linewidth=1)
    for positions, values in (([v-.08 for v in x], first), ([v+.08 for v in x], second)):
        for pos, value in zip(positions, values):
            ax.annotate(f'{round(value)} ms', (pos, value), xytext=(0, 8 if pos in [v-.08 for v in x] else -17),
                        textcoords='offset points', ha='center', fontsize=9)
    ax.set_xticks(x, ['No original preparation', '2 s original preparation\nRound 2', '2 s original preparation\nRound 3'])
    ax.set_ylabel('Tail delay relative to original (ms)\nNegative = tail ahead')
    ax.set_title('Prepared tails: same-client alignment only\nNormal JIT; JVM limited to 4 processors; 3 trials')
    ax.grid(axis='y', alpha=.2)
    ax.margins(x=.2, y=.3)
    ax.legend(loc='best', fontsize=9)
    for suffix in ('png', 'svg'):
        target = ROOT / ('docs/benchmarks/late-join-tails.' + suffix)
        fig.savefig(target, dpi=180, metadata={'Date': None} if suffix == 'svg' else None)
        if suffix == 'svg':
            target.write_text('\n'.join(line.rstrip() for line in target.read_text().splitlines()) + '\n')


if __name__ == '__main__':
    main()
