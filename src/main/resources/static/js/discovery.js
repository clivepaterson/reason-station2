function loadDiscoveryList(url, selector, renderItem, emptyMessage) {
    var list = document.querySelector(selector);
    var loading = document.querySelector(selector + '-loading');
    list.replaceChildren();
    list.setAttribute('aria-busy', 'true');
    list.hidden = true;
    loading.hidden = false;

    fetch(url)
        .then(function(response) {
            if (!response.ok) {
                throw new Error('Unable to load discovery content.');
            }
            return response.json();
        })
        .then(function(items) {
            list.replaceChildren();
            if (items.length === 0) {
                list.appendChild(document.createElement('li')).textContent = emptyMessage;
            } else {
                items.forEach(function(item) {
                    list.appendChild(renderItem(item));
                });
            }
            list.hidden = false;
            list.setAttribute('aria-busy', 'false');
            loading.hidden = true;
        })
        .catch(function() {
            list.replaceChildren(
                Object.assign(document.createElement('li'), {textContent: 'Unable to load this content.'}));
            list.hidden = false;
            list.setAttribute('aria-busy', 'false');
            loading.hidden = true;
        });
}

function createSidebarTrackItem(track, options) {
    var item = document.createElement('li');
    item.className = 'sidebar-track-item';

    var details = document.createElement('div');
    details.className = 'sidebar-item-details';
    item.appendChild(details);

    var title = document.createElement('strong');
    title.textContent = track.trackName;
    details.appendChild(title);

    var meta = document.createElement('span');
    meta.textContent = options.meta(track);
    details.appendChild(meta);

    var download = document.createElement('a');
    download.className = 'sidebar-download-link';
    download.href = '/api/tracks/' + encodeURIComponent(track.trackid) + '/download';
    download.setAttribute('aria-label', 'Download ' + track.trackName + ' by ' + track.artistAlias);
    download.title = 'Download ' + track.trackName;
    download.textContent = 'Download';
    item.appendChild(download);
    return item;
}

function formatUploadDate(value) {
    var date = new Date(value);
    var elapsedDays = Math.floor((Date.now() - date.getTime()) / 86400000);
    if (elapsedDays === 0) {
        return 'Today';
    }
    if (elapsedDays === 1) {
        return 'Yesterday';
    }
    if (elapsedDays < 7) {
        return elapsedDays + ' days ago';
    }
    return date.toLocaleDateString(undefined, {month: 'short', day: 'numeric'});
}

function formatArtistJoinDate(value) {
    var elapsedDays = Math.max(0, Math.floor((Date.now() - new Date(value).getTime()) / 86400000));
    if (elapsedDays === 0) {
        return 'Joined today';
    }

    var amount;
    var unit;
    if (elapsedDays < 7) {
        amount = elapsedDays;
        unit = 'day';
    } else if (elapsedDays < 30) {
        amount = Math.floor(elapsedDays / 7);
        unit = 'week';
    } else if (elapsedDays < 365) {
        amount = Math.floor(elapsedDays / 30);
        unit = 'month';
    } else {
        amount = Math.floor(elapsedDays / 365);
        unit = 'year';
    }

    return 'Joined ' + amount + ' ' + unit + (amount === 1 ? '' : 's') + ' ago';
}

window.loadDiscovery = function() {
    loadDiscoveryList('/api/discovery/artists-with-track-count', '#random-artist', function(artist) {
        var item = document.createElement('li');
        item.className = 'artist-discovery-item';
        var icon = document.createElement('span');
        icon.className = 'discovery-icon artist-icon';
        icon.setAttribute('aria-hidden', 'true');
        item.appendChild(icon);
        var details = document.createElement('div');
        details.className = 'artist-discovery-details';
        item.appendChild(details);
        var link = document.createElement('a');
        link.href = '/index.html?artistAlias=' + encodeURIComponent(artist.artistAlias);
        link.textContent = artist.artistAlias;
        details.appendChild(link);
        var count = document.createElement('span');
        count.className = 'artist-track-count';
        count.textContent = artist.trackCount
                + (artist.trackCount === 1 ? ' track' : ' tracks');
        details.appendChild(count);
        var dateCreated = document.createElement('div');
        dateCreated.className = 'artist-date-created';
        var calendar = document.createElement('span');
        calendar.className = 'artist-calendar-icon';
        calendar.setAttribute('aria-hidden', 'true');
        dateCreated.appendChild(calendar);
        var joined = document.createElement('span');
        joined.textContent = formatArtistJoinDate(artist.dateCreated);
        dateCreated.appendChild(joined);
        item.appendChild(dateCreated);
        var arrow = document.createElement('span');
        arrow.className = 'discovery-arrow';
        arrow.setAttribute('aria-hidden', 'true');
        item.appendChild(arrow);
        item.addEventListener('click', function(event) {
            if (!event.target.closest('a')) {
                link.click();
            }
        });
        return item;
    }, 'No users found.');

    loadDiscoveryList('/api/discovery/tracks', '#random-tracks', function(track) {
        var item = document.createElement('li');
        item.className = 'track-discovery-item track-card';
        item.appendChild(window.createMusicStyleIcon(track.musicStyle));
        var content = document.createElement('div');
        content.className = 'track-card-content';
        item.appendChild(content);
        var title = document.createElement('h3');
        title.textContent = track.trackName;
        content.appendChild(title);
        var artist = document.createElement('a');
        artist.className = 'track-artist-link';
        artist.href = '/index.html?artistAlias=' + encodeURIComponent(track.artistAlias);
        artist.textContent = 'by ' + track.artistAlias;
        content.appendChild(artist);
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
        link.setAttribute('aria-label', 'Download ' + track.trackName + ' by ' + track.artistAlias);
        link.textContent = 'Download';
        actions.appendChild(link);
        actions.appendChild(window.createRateTrackButton(track));
        return item;
    }, 'No tracks found.');

    loadDiscoveryList('/api/discovery/popular-tracks', '#popular-tracks', function(track) {
        return createSidebarTrackItem(track, {
            meta: function(item) {
                return item.artistAlias + ' \u00b7 ' + item.downloadCount
                        + (item.downloadCount === 1 ? ' download' : ' downloads');
            }
        });
    }, 'No downloads recorded this week.');

    loadDiscoveryList('/api/discovery/highest-rated-tracks', '#highest-rated-tracks', function(track) {
        return createSidebarTrackItem(track, {
            meta: function(item) {
                return item.artistAlias + ' \u00b7 ' + Number(item.averageTrackRating).toFixed(1)
                        + '/5 (' + item.ratingCount
                        + (item.ratingCount === 1 ? ' rating)' : ' ratings)');
            }
        });
    }, 'No ratings recorded this week.');

    loadDiscoveryList('/api/discovery/popular-artists', '#popular-artists', function(artist) {
        var item = document.createElement('li');
        item.className = 'sidebar-artist-item';
        var link = document.createElement('a');
        link.href = '/index.html?artistAlias=' + encodeURIComponent(artist.artistAlias);
        link.textContent = artist.artistAlias;
        item.appendChild(link);
        var count = document.createElement('span');
        count.textContent = artist.downloadCount
                + (artist.downloadCount === 1 ? ' download' : ' downloads');
        item.appendChild(count);
        return item;
    }, 'No artist downloads recorded this month.');

    loadDiscoveryList('/api/discovery/newest-tracks', '#newest-tracks', function(track) {
        return createSidebarTrackItem(track, {
            meta: function(item) {
                return item.artistAlias + ' \u00b7 ' + formatUploadDate(item.dateUploaded);
            }
        });
    }, 'No tracks have been uploaded yet.');
};

window.loadDiscovery();
