window.initializeTutorialsPage = function() {
    var tutorialImageSelector = '.tutorial-article-header img, .tutorial-screenshot img, .tutorial-download > img';

    function getOscillatorValue(waveform, partialCount, phase) {
        var value = 0;
        var partial;
        var harmonic;

        for (partial = 1; partial <= partialCount; partial++) {
            if (waveform === 'square') {
                harmonic = (partial * 2) - 1;
                value += Math.sin(harmonic * phase) / harmonic;
            } else if (waveform === 'triangle') {
                harmonic = (partial * 2) - 1;
                value += (partial % 2 === 1 ? 1 : -1) * Math.sin(harmonic * phase) / (harmonic * harmonic);
            } else {
                harmonic = partial;
                value += (harmonic % 2 === 1 ? 1 : -1) * Math.sin(harmonic * phase) / harmonic;
            }
        }

        return value;
    }

    function createSmoothPath(points) {
        var path = 'M' + points[0].x.toFixed(2) + ' ' + points[0].y.toFixed(2);
        var index;

        for (index = 0; index < points.length - 1; index++) {
            var previous = points[Math.max(0, index - 1)];
            var current = points[index];
            var next = points[index + 1];
            var following = points[Math.min(points.length - 1, index + 2)];
            var control1X = current.x + (next.x - previous.x) / 6;
            var control1Y = current.y + (next.y - previous.y) / 6;
            var control2X = next.x - (following.x - current.x) / 6;
            var control2Y = next.y - (following.y - current.y) / 6;

            path += ' C' + control1X.toFixed(2) + ' ' + control1Y.toFixed(2)
                + ' ' + control2X.toFixed(2) + ' ' + control2Y.toFixed(2)
                + ' ' + next.x.toFixed(2) + ' ' + next.y.toFixed(2);
        }

        return path;
    }

    $('.oscillator-progressions svg').each(function() {
        var progressionId = this.getAttribute('aria-labelledby') || '';
        var progression = /^(square|triangle|saw)-sum-(\d+)$/.exec(progressionId);
        var sampleCount = 128;
        var points = [];
        var sample;

        if (!progression) {
            return;
        }

        for (sample = 0; sample <= sampleCount; sample++) {
            var progress = sample / sampleCount;
            points.push({
                x: 20 + (600 * progress),
                y: 80 - (50 * getOscillatorValue(progression[1], Number(progression[2]), progress * Math.PI * 4))
            });
        }

        $(this).find('path[stroke="#ff9b25"]').attr('d', createSmoothPath(points));
    });

    $(tutorialImageSelector).each(function() {
        window.enableImageZoom(this);
    });
};
