(function() {
    var player;
    var audio;
    var playlist = [];
    var currentIndex = -1;
    var playbackRequested = false;
    var playRequest = 0;
    var lastTrigger;

    function audioUrl(track) {
        return '/api/tracks/' + encodeURIComponent(track.trackid) + '/audio';
    }

    function setStatus(message) {
        player.querySelector('.mp3-player-status').textContent = message;
    }

    function formatTime(seconds) {
        var total = Math.floor(seconds);
        var hours = Math.floor(total / 3600);
        var minutes = Math.floor(total % 3600 / 60);
        var remainder = String(total % 60).padStart(2, '0');
        return (hours ? hours + ':' + String(minutes).padStart(2, '0') : minutes) + ':' + remainder;
    }

    function renderTimeline(position, duration) {
        var seek = player.querySelector('.mp3-player-seek');
        var hasDuration = Number.isFinite(duration) && duration > 0;
        var currentTime = hasDuration && Number.isFinite(position)
            ? Math.max(0, Math.min(position, duration)) : 0;
        var elapsed = formatTime(currentTime);
        var total = hasDuration ? formatTime(duration) : '--:--';
        seek.disabled = !hasDuration;
        seek.max = hasDuration ? duration : 0;
        seek.value = currentTime;
        seek.setAttribute('aria-valuetext', elapsed + ' of ' + total);
        player.querySelector('.mp3-player-elapsed').textContent = elapsed;
        player.querySelector('.mp3-player-duration').textContent = total;
    }

    function updateTimeline() {
        renderTimeline(audio.currentTime, audio.getAttribute('src') && !audio.error ? audio.duration : NaN);
    }

    function controlIcon(name) {
        var paths = {
            play: 'M8 5v14l11-7z',
            pause: 'M6 5h4v14H6zM14 5h4v14h-4z',
            previous: 'M5 5h2v14H5zM19 5v14L8 12z',
            next: 'M17 5h2v14h-2zM5 5v14l11-7z',
            download: 'M11 3h2v10l4-4 1.4 1.4L12 16.8l-6.4-6.4L7 9l4 4zM4 17h2v3h12v-3h2v5H4z'
        };
        return '<svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor"'
            + ' aria-hidden="true" focusable="false"><path d="' + paths[name] + '"/></svg>';
    }

    function updateControls() {
        var playing = playbackRequested && !audio.paused;
        var play = player.querySelector('[data-player-action="play"]');
        var label = playing ? 'Pause track' : 'Play track';
        play.innerHTML = controlIcon(playing ? 'pause' : 'play');
        play.setAttribute('aria-label', label);
        play.title = label;
        player.querySelector('[data-player-action="previous"]').disabled = currentIndex <= 0;
        player.querySelector('[data-player-action="next"]').disabled = currentIndex >= playlist.length - 1;
    }

    function renderPlaylist() {
        var list = player.querySelector('.mp3-player-playlist');
        list.replaceChildren();
        playlist.forEach(function(track, index) {
            var item = document.createElement('li');
            var row = document.createElement('div');
            row.className = 'mp3-player-playlist-row';
            var button = document.createElement('button');
            button.className = 'mp3-player-track';
            button.type = 'button';
            button.textContent = track.trackName + ' - ' + track.artistAlias;
            if (index === currentIndex) {
                button.setAttribute('aria-current', 'true');
            }
            button.addEventListener('click', function() {
                selectTrack(index);
            });
            row.appendChild(button);
            var remove = document.createElement('button');
            remove.type = 'button';
            remove.className = 'mp3-player-remove-track';
            remove.innerHTML = '&times;';
            remove.title = 'Remove from playlist';
            remove.setAttribute('aria-label', 'Remove ' + track.trackName + ' by ' + track.artistAlias + ' from playlist');
            remove.addEventListener('click', function() {
                removeTrack(index);
            });
            row.appendChild(remove);
            item.appendChild(row);
            list.appendChild(item);
        });
        player.querySelector('summary').textContent = 'Playlist (' + playlist.length + ')';
        updateControls();
    }

    function closePlayer() {
        stop();
        audio.removeAttribute('src');
        audio.load();
        renderTimeline(0, NaN);
        playlist = [];
        currentIndex = -1;
        player.hidden = true;
        if (lastTrigger && lastTrigger.isConnected) {
            lastTrigger.focus();
        }
    }

    function removeTrack(index) {
        var wasCurrent = index === currentIndex;
        var removed = playlist.splice(index, 1)[0];
        if (!playlist.length) {
            closePlayer();
            return;
        }
        if (wasCurrent) {
            selectTrack(Math.min(index, playlist.length - 1));
        } else {
            if (index < currentIndex) {
                currentIndex--;
            }
            renderPlaylist();
        }
        setStatus('Removed from playlist: ' + removed.trackName);
        player.querySelectorAll('.mp3-player-remove-track')[Math.min(index, playlist.length - 1)].focus();
    }

    function play() {
        var request = ++playRequest;
        playbackRequested = true;
        setStatus('');
        if (audio.error) {
            audio.load();
        }
        audio.play().catch(function(error) {
            // Ignore a superseded play request after pausing, switching tracks, or closing.
            if (request !== playRequest) {
                return;
            }
            playbackRequested = false;
            setStatus(error.name === 'NotAllowedError'
                ? 'Playback was blocked by your browser. Press Play to listen.'
                : 'Unable to play this MP3. Try Play again or skip to another track.');
            updateControls();
        });
    }

    function stop() {
        ++playRequest;
        playbackRequested = false;
        audio.pause();
        audio.currentTime = 0;
        updateTimeline();
        updateControls();
    }

    function selectTrack(index) {
        stop();
        currentIndex = index;
        var track = playlist[index];
        var art = window.createTrackArt(track);
        art.className = 'mp3-player-art';
        player.querySelector('.mp3-player-art').replaceWith(art);
        player.querySelector('.mp3-player-title').textContent = track.trackName;
        player.querySelector('.mp3-player-artist').textContent = track.artistAlias;
        player.querySelector('.mp3-player-style').textContent = track.musicStyle;
        var projectDownload = player.querySelector('.mp3-player-project-download');
        projectDownload.href = '/api/tracks/' + encodeURIComponent(track.trackid) + '/download';
        projectDownload.setAttribute('aria-label', 'Download Reason project for ' + track.trackName + ' by ' + track.artistAlias);
        var download = player.querySelector('.mp3-player-mp3-download');
        download.href = audioUrl(track);
        download.setAttribute('aria-label', 'Download MP3 for ' + track.trackName + ' by ' + track.artistAlias);
        audio.src = audioUrl(track);
        renderTimeline(0, NaN);
        renderPlaylist();
        play();
    }

    function createPlayer() {
        player = document.createElement('section');
        player.className = 'mp3-player';
        player.setAttribute('aria-label', 'MP3 player');
        player.hidden = true;
        player.innerHTML = [
            '<div class="mp3-player-bar">',
            '  <img class="mp3-player-art" alt="">',
            '  <div class="mp3-player-info">',
            '    <strong class="mp3-player-title"></strong>',
            '    <span class="mp3-player-artist"></span>',
            '    <span class="mp3-player-style"></span>',
            '  </div>',
            '  <div class="mp3-player-controls">',
            '    <button type="button" data-player-action="previous" aria-label="Previous track" title="Previous track" disabled>'
                + controlIcon('previous') + '</button>',
            '    <button type="button" data-player-action="play" aria-label="Play track" title="Play track">'
                + controlIcon('play') + '</button>',
            '    <button type="button" data-player-action="next" aria-label="Next track" title="Next track" disabled>'
                + controlIcon('next') + '</button>',
            '  </div>',
            '  <label class="mp3-player-volume">Volume',
            '    <input type="range" min="0" max="1" step="0.01" value="0.8" aria-label="Volume">',
            '  </label>',
            '  <div class="mp3-player-downloads">',
            '    <a class="mp3-player-download mp3-player-project-download" title="Download Reason project" download>',
            '      <img src="/images/reason-download.png" width="24" height="24" alt="">',
            '      ' + controlIcon('download'),
            '    </a>',
            '    <a class="mp3-player-download mp3-player-mp3-download" title="Download MP3" download>',
            '      <span class="mp3-player-music-note" aria-hidden="true">&#9835;</span>',
            '      ' + controlIcon('download'),
            '    </a>',
            '  </div>',
            '  <button type="button" data-player-action="close" aria-label="Close MP3 player" title="Close player">&times;</button>',
            '</div>',
            '<div class="mp3-player-timeline">',
            '  <span class="mp3-player-elapsed">0:00</span>',
            '  <input class="mp3-player-seek" type="range" min="0" max="0" step="0.1" value="0"',
            '         aria-label="Seek audio" aria-valuetext="0:00 of --:--" disabled>',
            '  <span class="mp3-player-duration">--:--</span>',
            '</div>',
            '<details class="mp3-player-queue" open>',
            '  <summary>Playlist</summary>',
            '  <ol class="mp3-player-playlist"></ol>',
            '</details>',
            '<p class="mp3-player-status" role="status" aria-live="polite"></p>'
        ].join('\n');
        audio = document.createElement('audio');
        audio.preload = 'none';
        audio.volume = 0.8;
        player.appendChild(audio);
        document.body.appendChild(player);

        var spacer = document.createElement('div');
        spacer.className = 'mp3-player-spacer';
        spacer.setAttribute('aria-hidden', 'true');
        document.body.appendChild(spacer);
        new ResizeObserver(function() {
            var height = player.hidden ? 0 : player.offsetHeight;
            spacer.style.height = height + 'px';
            document.documentElement.style.setProperty('--mp3-player-height', height + 'px');
        }).observe(player);

        player.querySelector('[data-player-action="play"]').addEventListener('click', function() {
            if (playbackRequested) {
                ++playRequest;
                playbackRequested = false;
                audio.pause();
                updateControls();
            } else {
                play();
            }
        });
        player.querySelector('[data-player-action="previous"]').addEventListener('click', function() {
            if (currentIndex > 0) {
                selectTrack(currentIndex - 1);
            }
        });
        player.querySelector('[data-player-action="next"]').addEventListener('click', function() {
            if (currentIndex < playlist.length - 1) {
                selectTrack(currentIndex + 1);
            }
        });
        player.querySelector('[data-player-action="close"]').addEventListener('click', closePlayer);
        player.querySelector('.mp3-player-volume input').addEventListener('input', function(event) {
            audio.volume = Number(event.target.value);
        });
        player.querySelector('.mp3-player-seek').addEventListener('input', function(event) {
            if (!event.target.disabled) {
                audio.currentTime = Math.max(0, Math.min(Number(event.target.value), audio.duration));
                updateTimeline();
            }
        });
        ['loadedmetadata', 'durationchange', 'timeupdate', 'seeking', 'seeked', 'emptied'].forEach(function(eventName) {
            audio.addEventListener(eventName, updateTimeline);
        });
        audio.addEventListener('play', updateControls);
        audio.addEventListener('pause', updateControls);
        audio.addEventListener('ended', function() {
            if (currentIndex < playlist.length - 1) {
                selectTrack(currentIndex + 1);
            } else {
                playbackRequested = false;
                setStatus('Playlist finished.');
                updateControls();
            }
        });
        audio.addEventListener('error', function() {
            if (!audio.getAttribute('src')) {
                return;
            }
            ++playRequest;
            playbackRequested = false;
            setStatus('Unable to load this MP3. Try Play again or skip to another track.');
            renderTimeline(0, NaN);
            updateControls();
        });
    }

    window.bindTrackAudioLink = function(link, track) {
        link.textContent = 'Play MP3';
        link.setAttribute('aria-label', 'Play or queue MP3 for ' + track.trackName + ' by ' + track.artistAlias);
        link.title = 'Play MP3, or add to the playlist if music is already playing';
        var filesize = document.createElement('span');
        filesize.className = 'track-link-filesize';
        link.appendChild(filesize);
        var storedSize = window.formatFileSize(track.audioFilesize);
        if (storedSize) {
            filesize.textContent = ' (' + storedSize + ')';
        } else {
            fetch('/api/tracks/' + encodeURIComponent(track.trackid) + '/audio/metadata', {cache: 'no-cache'})
                .then(function(response) {
                    if (!response.ok) {
                        throw new Error('Unable to load MP3 file size.');
                    }
                    return response.json();
                })
                .then(function(metadata) {
                    var size = window.formatFileSize(metadata.filesize);
                    if (!size) {
                        throw new Error('Invalid MP3 file size.');
                    }
                    track.audioFilesize = metadata.filesize;
                    filesize.textContent = ' (' + size + ')';
                })
                .catch(function(error) {
                    filesize.textContent = ' (size unavailable)';
                    console.error('Unable to load MP3 file size for track ' + track.trackid, error);
                });
        }
        link.addEventListener('click', function(event) {
            if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || event.button !== 0) {
                return;
            }
            event.preventDefault();
            if (!player) {
                createPlayer();
            }
            lastTrigger = link;
            player.hidden = false;
            playlist.push(track);
            if (playbackRequested) {
                renderPlaylist();
                setStatus('Added to playlist: ' + track.trackName);
            } else {
                selectTrack(playlist.length - 1);
            }
        });
    };
}());
