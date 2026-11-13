(function() {
    function appendTextElement(parent, tagName, className, text) {
        var element = document.createElement(tagName);
        element.className = className;
        element.textContent = text;
        parent.appendChild(element);
        return element;
    }

    window.formatFileSize = function(filesize) {
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
    };

    window.formatTrackFilename = function(track, fallback) {
        var filename = track.filename || fallback || 'Unnamed file';
        return filename + (track.programVersion ? ' (Reason ' + track.programVersion + ')' : '');
    };

    window.createTrackCard = function(track, options) {
        options = options || {};

        var item = document.createElement(options.tagName || 'article');
        item.className = ['track-card', options.className].filter(Boolean).join(' ');
        item.appendChild(window.createTrackArt(track));

        var downloadUrl = '/api/tracks/' + encodeURIComponent(track.trackid) + '/download';
        var downloadLabel = 'Download ' + track.trackName
            + (options.includeArtistInDownloadLabel === false ? '' : ' by ' + track.artistAlias);
        var content = document.createElement('div');
        content.className = 'track-card-content';
        item.appendChild(content);

        var heading = document.createElement('div');
        heading.className = 'track-card-heading';
        var title = document.createElement('h3');
        var titleLink = document.createElement('a');
        titleLink.href = '/tracks/' + encodeURIComponent(track.trackid);
        titleLink.textContent = track.trackName;
        title.appendChild(titleLink);
        heading.appendChild(title);
        appendTextElement(heading, 'span', 'track-style-badge', track.musicStyle);
        content.appendChild(heading);

        if (options.showArtist) {
            var artist = document.createElement('a');
            artist.className = 'track-artist-link';
            artist.href = '/index.html?artistAlias=' + encodeURIComponent(track.artistAlias);
            artist.textContent = 'by ' + track.artistAlias;
            content.appendChild(artist);
        }

        var filename = document.createElement('a');
        filename.className = 'track-file-meta';
        filename.href = downloadUrl;
        filename.setAttribute('aria-label', downloadLabel);
        filename.textContent = window.formatTrackFilename(track, options.filenameFallback);
        var formattedFilesize = window.formatFileSize(track.filesize);
        if (formattedFilesize) {
            appendTextElement(filename, 'span', 'track-link-filesize', ' (' + formattedFilesize + ')');
        }
        content.appendChild(filename);

        if (track.audioUri && track.audioUri.trim()) {
            var mp3Link = document.createElement('a');
            mp3Link.className = 'track-file-meta track-mp3-link';
            mp3Link.href = '/api/tracks/' + encodeURIComponent(track.trackid) + '/audio';
            window.bindTrackAudioLink(mp3Link, track);
            content.appendChild(mp3Link);
        }

        if (track.audioLink) {
            var audioLink = document.createElement('a');
            audioLink.className = 'track-file-meta track-audio-link';
            audioLink.href = track.audioLink;
            audioLink.target = '_blank';
            audioLink.rel = 'noopener noreferrer';
            audioLink.textContent = track.audioLink;
            content.appendChild(audioLink);
        }

        var metadata = document.createElement('div');
        metadata.className = 'track-card-meta';
        appendTextElement(metadata, 'span', 'track-downloads-meta', 'Downloads: ' + track.downloadCount);
        window.addTrackRatingMeta(metadata, track);
        if (track.dateUploaded) {
            var uploaded = appendTextElement(metadata, 'time', 'track-uploaded-meta', 'Uploaded: '
                + new Intl.DateTimeFormat(undefined, {dateStyle: 'medium'})
                    .format(new Date(track.dateUploaded)));
            uploaded.dateTime = track.dateUploaded;
        }
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
        actions.appendChild(window.createCopyLinkButton(titleLink.href, track.trackName));
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
