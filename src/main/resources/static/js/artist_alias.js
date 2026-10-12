window.initializeArtistPage = function() {
    var params = new URLSearchParams(window.location.search);
    var artistAlias = params.get('artistAlias');
    var heading = document.getElementById('artist-alias-heading');
    var tracksList = document.getElementById('artist-tracks');
    var loading = document.getElementById('artist-tracks-loading');

    if (!artistAlias) {
        heading.textContent = 'Artist not found';
        tracksList.replaceChildren();
        tracksList.hidden = false;
        loading.hidden = true;
        return;
    }

    heading.textContent = artistAlias;
    document.title = 'Reason Station - ' + artistAlias;
    tracksList.replaceChildren();
    tracksList.hidden = true;
    tracksList.setAttribute('aria-busy', 'true');
    loading.hidden = false;
    fetch('/api/discovery/artist-tracks?artistAlias=' + encodeURIComponent(artistAlias))
        .then(function(response) {
            if (!response.ok) {
                throw new Error('Unable to load artist tracks.');
            }
            return response.json();
        })
        .then(function(tracks) {
            tracksList.replaceChildren();
            if (tracks.length === 0) {
                tracksList.appendChild(document.createElement('p')).textContent = 'No tracks found.';
            } else {
                tracks.forEach(function(track) {
                    tracksList.appendChild(window.createTrackCard(track));
                });
            }
            tracksList.hidden = false;
            tracksList.setAttribute('aria-busy', 'false');
            loading.hidden = true;
        })
        .catch(function() {
            tracksList.replaceChildren();
            tracksList.appendChild(document.createElement('p')).textContent = 'Unable to load artist tracks.';
            tracksList.hidden = false;
            tracksList.setAttribute('aria-busy', 'false');
            loading.hidden = true;
        });
};
