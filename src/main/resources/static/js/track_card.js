(function() {
    var dialog;
    var dialogContent;
    var requestId = 0;

    function ensureCardDialog() {
        if (dialog) {
            return;
        }
        dialog = document.createElement('dialog');
        dialog.className = 'track-card-dialog';
        dialog.setAttribute('aria-label', 'Track details');
        var close = document.createElement('button');
        close.type = 'button';
        close.className = 'dialog-close';
        close.setAttribute('aria-label', 'Close track details');
        close.textContent = '\u00d7';
        close.addEventListener('click', function() { dialog.close(); });
        dialogContent = document.createElement('div');
        dialogContent.setAttribute('aria-live', 'polite');
        dialog.append(close, dialogContent);
        document.body.appendChild(dialog);
        dialog.addEventListener('mousedown', function(event) {
            if (event.target === dialog) {
                var bounds = dialog.getBoundingClientRect();
                if (event.clientX < bounds.left || event.clientX > bounds.right
                        || event.clientY < bounds.top || event.clientY > bounds.bottom) {
                    dialog.close();
                }
            }
        });
        dialog.addEventListener('close', function() {
            if (dialog.open) {
                return;
            }
            requestId++;
            dialogContent.replaceChildren();
        });
    }

    document.addEventListener('click', function(event) {
        if (event.defaultPrevented || event.button !== 0
                || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) {
            return;
        }
        var link = event.target.closest('a[href]');
        if (!link || link.hasAttribute('download') || (link.target && link.target !== '_self')) {
            return;
        }
        var url = new URL(link.href);
        var match = /^\/(tracks|patches)\/([1-9]\d*)\/?$/.exec(url.pathname);
        if (url.origin !== window.location.origin || !match) {
            return;
        }
        event.preventDefault();
        ensureCardDialog();
        var resource = match[1];
        var type = resource === 'patches' ? 'patch' : 'track';
        dialog.setAttribute('aria-label', resource === 'patches' ? 'Patch details' : 'Track details');
        dialog.querySelector('.dialog-close').setAttribute('aria-label', 'Close ' + type + ' details');
        var currentRequest = ++requestId;
        var status = document.createElement('p');
        status.className = 'form-message';
        status.textContent = 'Loading ' + type + '...';
        dialogContent.replaceChildren(status);
        dialogContent.setAttribute('aria-busy', 'true');
        if (!dialog.open) {
            dialog.showModal();
        }
        fetch('/api/discovery/' + resource + '/' + match[2])
            .then(function(response) {
                if (!response.ok) {
                    throw new Error(response.status === 404
                        ? 'This ' + type + ' could not be found.'
                        : 'Unable to load this ' + type + '. Please try again.');
                }
                return response.json();
            })
            .then(function(item) {
                if (currentRequest === requestId && dialog.open) {
                    dialogContent.replaceChildren(resource === 'patches'
                        ? window.createPatchCard(item) : window.createTrackCard(item, {showArtist: true}));
                }
            })
            .catch(function(error) {
                if (currentRequest === requestId && dialog.open) {
                    status.className = 'form-message error';
                    status.textContent = error.message;
                }
            })
            .finally(function() {
                if (currentRequest === requestId) {
                    dialogContent.setAttribute('aria-busy', 'false');
                }
            });
    });

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
        var shareActions = document.createElement('div');
        shareActions.className = 'track-card-share-actions';
        shareActions.appendChild(window.createCopyLinkButton(titleLink.href, track.trackName));
        if (track.audioUri && track.audioUri.trim()) {
            shareActions.appendChild(window.createCopyEmbedButton(track));
        }
        actions.appendChild(shareActions);
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
