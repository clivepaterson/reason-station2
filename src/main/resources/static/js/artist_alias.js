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
                    var item = document.createElement('article');
                    item.className = 'track-card';
                    item.appendChild(window.createMusicStyleIcon(track.musicStyle));
                    var content = document.createElement('div');
                    content.className = 'track-card-content';
                    item.appendChild(content);
                    var title = document.createElement('h3');
                    title.textContent = track.trackName;
                    content.appendChild(title);
                    var badge = document.createElement('span');
                    badge.className = 'track-style-badge';
                    badge.textContent = track.musicStyle;
                    content.appendChild(badge);
                    var filename = document.createElement('a');
                    filename.className = 'track-file-meta';
                    filename.href = '/api/tracks/' + encodeURIComponent(track.trackid) + '/download';
                    filename.setAttribute('aria-label', 'Download ' + track.trackName + ' by ' + track.artistAlias);
                    filename.textContent = track.filename || 'Unnamed file';
                    content.appendChild(filename);
                    var metadata = document.createElement('div');
                    metadata.className = 'track-card-meta';
                    content.appendChild(metadata);
                    var style = document.createElement('span');
                    style.className = 'track-style-meta';
                    style.textContent = 'Style: ' + track.musicStyle;
                    metadata.appendChild(style);
                    var downloads = document.createElement('span');
                    downloads.className = 'track-downloads-meta';
                    downloads.textContent = 'Downloads: ' + track.downloadCount;
                    metadata.appendChild(downloads);
                    window.addTrackRatingMeta(metadata, track);
                    var actions = document.createElement('div');
                    actions.className = 'track-card-actions';
                    item.appendChild(actions);
                    var link = document.createElement('a');
                    link.className = 'download-track-link';
                    link.href = '/api/tracks/' + encodeURIComponent(track.trackid) + '/download';
                    link.textContent = 'Download';
                    actions.appendChild(link);
                    actions.appendChild(window.createRateTrackButton(track));
                    tracksList.appendChild(item);
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
