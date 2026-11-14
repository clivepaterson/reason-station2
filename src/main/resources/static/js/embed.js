(function() {
    var status = document.getElementById('embed-status');
    var trackId = new URLSearchParams(window.location.search).get('track');
    if (!trackId || !/^[1-9]\d*$/.test(trackId)) {
        status.textContent = 'A valid track ID is required.';
        return;
    }
    fetch('/api/discovery/tracks/' + encodeURIComponent(trackId), {credentials: 'omit'})
        .then(function(response) {
            if (!response.ok) {
                throw new Error(response.status === 404 ? 'This track is unavailable.'
                    : 'Unable to load this track. Please reload the player.');
            }
            return response.json();
        })
        .then(function(track) {
            if (!track.audioUri || !track.audioUri.trim()) {
                status.textContent = 'This track does not have an MP3.';
                return;
            }
            document.title = track.trackName + ' by ' + track.artistAlias + ' | Reason Station';
            window.mountEmbeddedTrackPlayer(track);
            var header = document.querySelector('.embed-page > .navbar');
            var bar = document.querySelector('.mp3-player-embedded .mp3-player-bar');
            bar.classList.add('navbar');
            var trackInfo = document.createElement('div');
            trackInfo.className = 'embed-track-info';
            trackInfo.append(bar.querySelector('.mp3-player-art'), bar.querySelector('.mp3-player-info'));
            var actions = document.createElement('div');
            actions.className = 'embed-actions';
            actions.append(bar.querySelector('.mp3-player-controls'), bar.querySelector('.mp3-player-volume'),
                bar.querySelector('.mp3-player-downloads'));
            bar.replaceChildren(trackInfo, header.querySelector('.logo-title'), actions);
            header.remove();
            status.remove();
        })
        .catch(function(error) {
            status.textContent = error.message;
            console.error('Unable to load embedded track ' + trackId, error);
        });
}());
