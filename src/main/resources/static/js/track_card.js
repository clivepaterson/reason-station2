(function() {
    function appendTextElement(parent, tagName, className, text) {
        var element = document.createElement(tagName);
        element.className = className;
        element.textContent = text;
        parent.appendChild(element);
        return element;
    }

    function formatFileSize(filesize) {
        if (typeof filesize !== 'number' || !Number.isFinite(filesize) || filesize < 0) {
            return null;
        }

        if (filesize < 1024) {
            return filesize.toLocaleString() + (filesize === 1 ? ' byte' : ' bytes');
        }

        if (filesize < 1024 * 1024) {
            return (Math.round(filesize / 1024 * 10) / 10).toLocaleString() + ' Kb';
        }

        return (Math.round(filesize / (1024 * 1024) * 10) / 10).toLocaleString() + ' Mb';
    }

    window.createTrackCard = function(track, options) {
        options = options || {};

        var item = document.createElement(options.tagName || 'article');
        item.className = ['track-card', options.className].filter(Boolean).join(' ');
        item.appendChild(window.createMusicStyleIcon(track.musicStyle));

        var downloadUrl = '/api/tracks/' + encodeURIComponent(track.trackid) + '/download';
        var downloadLabel = 'Download ' + track.trackName
            + (options.includeArtistInDownloadLabel === false ? '' : ' by ' + track.artistAlias);
        var content = document.createElement('div');
        content.className = 'track-card-content';
        item.appendChild(content);

        var title = document.createElement('h3');
        var titleDownload = document.createElement('a');
        titleDownload.href = downloadUrl;
        titleDownload.setAttribute('aria-label', downloadLabel);
        titleDownload.textContent = track.trackName;
        title.appendChild(titleDownload);
        content.appendChild(title);

        if (options.showArtist) {
            var artist = document.createElement('a');
            artist.className = 'track-artist-link';
            artist.href = '/index.html?artistAlias=' + encodeURIComponent(track.artistAlias);
            artist.textContent = 'by ' + track.artistAlias;
            content.appendChild(artist);
        }

        appendTextElement(content, 'span', 'track-style-badge', track.musicStyle);

        var filename = document.createElement('a');
        filename.className = 'track-file-meta';
        filename.href = downloadUrl;
        filename.setAttribute('aria-label', downloadLabel);
        filename.textContent = track.filename || options.filenameFallback || 'Unnamed file';
        content.appendChild(filename);

        var metadata = document.createElement('div');
        metadata.className = 'track-card-meta';
        appendTextElement(metadata, 'span', 'track-style-meta', 'Style: ' + track.musicStyle);
        var formattedFilesize = formatFileSize(track.filesize);
        if (formattedFilesize) {
            appendTextElement(metadata, 'span', 'track-filesize-meta', 'Size: ' + formattedFilesize);
        }
        appendTextElement(metadata, 'span', 'track-downloads-meta', 'Downloads: ' + track.downloadCount);
        window.addTrackRatingMeta(metadata, track);
        content.appendChild(metadata);

        var actions = document.createElement('div');
        actions.className = 'track-card-actions';
        var download = document.createElement('a');
        download.className = 'download-track-link';
        download.href = downloadUrl;
        download.setAttribute('aria-label', downloadLabel);
        download.textContent = 'Download';
        actions.appendChild(download);
        actions.appendChild(window.createRateTrackButton(track));
        item.appendChild(actions);

        if (options.deletable) {
            var deleteButton = document.createElement('button');
            deleteButton.className = 'delete-track-button';
            deleteButton.type = 'button';
            deleteButton.dataset.trackId = track.trackid;
            deleteButton.setAttribute('aria-label', 'Delete track');
            deleteButton.title = 'Delete track';

            var deleteIcon = document.createElement('img');
            deleteIcon.src = '/images/trash-can.svg';
            deleteIcon.alt = '';
            deleteButton.appendChild(deleteIcon);
            item.appendChild(deleteButton);
        }

        return item;
    };
}());
