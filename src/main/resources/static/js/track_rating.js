(function() {
    var dialog;
    var form;
    var status;
    var title;
    var activeTrack;

    function ensureDialog() {
        if (dialog) {
            return;
        }

        dialog = document.createElement('dialog');
        dialog.className = 'rating-dialog';
        dialog.setAttribute('aria-labelledby', 'rating-dialog-title');
        dialog.innerHTML =
            '<form class="rating-dialog-card" method="dialog">' +
                '<button class="dialog-close" type="button" aria-label="Close rating dialog">&times;</button>' +
                '<h2 id="rating-dialog-title">Rate track</h2>' +
                '<p class="rating-track-name"></p>' +
                '<fieldset class="star-rating">' +
                    '<legend>Choose a rating</legend>' +
                    '<input id="track-rating-5" name="rating" type="radio" value="5" required>' +
                    '<label for="track-rating-5" title="5 stars"><span class="sr-only">5 stars</span></label>' +
                    '<input id="track-rating-4" name="rating" type="radio" value="4">' +
                    '<label for="track-rating-4" title="4 stars"><span class="sr-only">4 stars</span></label>' +
                    '<input id="track-rating-3" name="rating" type="radio" value="3">' +
                    '<label for="track-rating-3" title="3 stars"><span class="sr-only">3 stars</span></label>' +
                    '<input id="track-rating-2" name="rating" type="radio" value="2">' +
                    '<label for="track-rating-2" title="2 stars"><span class="sr-only">2 stars</span></label>' +
                    '<input id="track-rating-1" name="rating" type="radio" value="1">' +
                    '<label for="track-rating-1" title="1 star"><span class="sr-only">1 star</span></label>' +
                '</fieldset>' +
                '<button class="cta-button rating-submit" type="submit">Save rating</button>' +
                '<p class="form-message rating-status" aria-live="polite"></p>' +
            '</form>';
        document.body.appendChild(dialog);

        form = dialog.querySelector('form');
        status = dialog.querySelector('.rating-status');
        title = dialog.querySelector('.rating-track-name');

        dialog.querySelector('.dialog-close').addEventListener('click', function() {
            dialog.close();
        });
        dialog.addEventListener('click', function(event) {
            if (event.target === dialog) {
                dialog.close();
            }
        });
        form.addEventListener('submit', submitRating);
    }

    function submitRating(event) {
        event.preventDefault();
        var selected = form.querySelector('input[name="rating"]:checked');
        if (!selected || !activeTrack) {
            status.className = 'form-message rating-status error';
            status.textContent = 'Choose a rating from 1 to 5 stars.';
            return;
        }

        var submit = form.querySelector('.rating-submit');
        submit.disabled = true;
        status.className = 'form-message rating-status';
        status.textContent = 'Saving...';
        fetch('/api/tracks/' + encodeURIComponent(activeTrack.trackid) + '/rating', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({rating: Number(selected.value)})
        })
            .then(function(response) {
                var contentType = response.headers.get('content-type') || '';
                if (response.redirected || !contentType.includes('application/json')) {
                    throw new Error('Sign in to rate tracks.');
                }
                return response.json().then(function(body) {
                    if (!response.ok) {
                        throw new Error(body.error || 'Unable to save your rating.');
                    }
                    return body;
                });
            })
            .then(function() {
                dialog.close();
                var artistPage = document.getElementById('artist-page');
                if (artistPage && !artistPage.hidden) {
                    window.location.reload();
                }
            })
            .catch(function(error) {
                status.className = 'form-message rating-status error';
                status.textContent = error.message;
            })
            .finally(function() {
                submit.disabled = false;
            });
    }

    window.addTrackRatingMeta = function(metadata, track) {
        if (track.averageTrackRating === null || track.averageTrackRating === undefined) {
            return;
        }
        var average = Math.max(0, Math.min(5, Number(track.averageTrackRating)));
        var rating = document.createElement('span');
        rating.className = 'track-rating-meta';
        rating.setAttribute('aria-label', 'Average rating: ' + average.toFixed(1) + ' out of 5 stars');

        var stars = document.createElement('span');
        stars.className = 'track-rating-stars';
        stars.setAttribute('aria-hidden', 'true');
        stars.style.setProperty('--rating-percent', (average / 5 * 100) + '%');
        rating.appendChild(stars);

        var value = document.createElement('span');
        value.className = 'track-rating-value';
        value.setAttribute('aria-hidden', 'true');
        value.textContent = average.toFixed(1);
        rating.appendChild(value);
        metadata.appendChild(rating);
    };

    window.createRateTrackButton = function(track) {
        var button = document.createElement('button');
        button.className = 'rate-track-button';
        button.type = 'button';
        button.textContent = 'Rate track';
        button.setAttribute('aria-label', 'Rate ' + track.trackName);
        button.addEventListener('click', function() {
            ensureDialog();
            activeTrack = track;
            form.reset();
            title.textContent = track.trackName + ' by ' + track.artistAlias;
            status.className = 'form-message rating-status';
            status.textContent = '';
            dialog.showModal();
            form.querySelector('input[name="rating"]').focus();
        });
        return button;
    };
}());
