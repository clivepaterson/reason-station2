(function() {
    function appendText(parent, tag, className, text) {
        var element = document.createElement(tag);
        element.className = className;
        element.textContent = text;
        parent.appendChild(element);
        return element;
    }

    window.createPatchCard = function(patch) {
        var id = encodeURIComponent(patch.patchid);
        var item = document.createElement('article');
        item.className = 'track-card patch-card';
        var image = document.createElement('img');
        image.className = 'track-card-art patch-card-image';
        image.src = patch.patchImgUri ? '/api/patches/' + id + '/image' : '/images/icon-patch.svg';
        image.alt = patch.patchImgUri ? 'Image for ' + patch.patchName : '';
        image.loading = 'lazy';
        if (patch.patchImgUri) {
            window.enableImageZoom(image);
        }
        item.appendChild(image);

        var content = document.createElement('div');
        content.className = 'track-card-content';
        item.appendChild(content);
        var heading = appendText(content, 'div', 'patch-card-heading', '');
        var identity = appendText(heading, 'div', 'patch-card-identity', '');
        var title = appendText(identity, 'h3', '', '');
        var link = appendText(title, 'a', 'patch-detail-link', patch.patchName);
        link.href = '/patches/' + id;
        var artist = appendText(identity, 'a', 'track-artist-link', 'by ' + patch.artistAlias);
        artist.href = '/index.html?artistAlias=' + encodeURIComponent(patch.artistAlias);
        if (patch.description) {
            appendText(heading, 'p', 'patch-description', 'Description: ' + patch.description);
        }
        appendText(content, 'span', 'track-style-badge', patch.ext || 'Unknown');
        var filename = appendText(content, 'a', 'track-file-meta', patch.filename);
        filename.href = '/api/patches/' + id + '/download';
        var metadata = appendText(content, 'div', 'track-card-meta patch-card-meta', '');
        var formattedFilesize = window.formatFileSize(patch.filesize);
        if (formattedFilesize) {
            appendText(metadata, 'span', 'track-filesize-meta', 'Size: ' + formattedFilesize);
        }
        if (patch.dateUploaded) {
            var date = appendText(metadata, 'time', 'patch-uploaded-meta', 'Uploaded: '
                + new Intl.DateTimeFormat(undefined, {dateStyle: 'medium'})
                    .format(new Date(patch.dateUploaded)));
            date.dateTime = patch.dateUploaded;
        }
        window.addPatchRatingMeta(metadata, patch);
        var actions = appendText(item, 'div', 'track-card-actions', '');
        var download = appendText(actions, 'a', 'download-track-link', 'Download');
        download.href = filename.href;
        download.setAttribute('aria-label', 'Download ' + patch.patchName + ' by ' + patch.artistAlias);
        actions.appendChild(window.createRatePatchButton(patch));
        actions.appendChild(window.createCopyLinkButton(link.href, patch.patchName));
        return item;
    };
}());
