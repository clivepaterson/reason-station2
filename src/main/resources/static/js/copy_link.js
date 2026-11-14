(function() {
    var dialog;
    var field;
    var status;
    var copyAttempt = 0;

    function ensureDialog() {
        if (dialog) {
            return;
        }

        dialog = document.createElement('dialog');
        dialog.className = 'copy-link-dialog';
        dialog.setAttribute('aria-labelledby', 'copy-link-dialog-title');
        dialog.innerHTML =
            '<button class="dialog-close" type="button" aria-label="Close link dialog">&times;</button>' +
            '<h2 id="copy-link-dialog-title">Copy link</h2>' +
            '<label class="sr-only" for="copy-link-field">Shareable link</label>' +
            '<textarea id="copy-link-field" rows="4" readonly></textarea>' +
            '<p class="copy-link-status" role="status"></p>';
        document.body.appendChild(dialog);
        field = dialog.querySelector('textarea');
        status = dialog.querySelector('.copy-link-status');
        dialog.querySelector('.dialog-close').addEventListener('click', function() {
            dialog.close();
        });
        dialog.addEventListener('mousedown', function(event) {
            if (event.target === dialog) {
                dialog.close();
            }
        });
        field.addEventListener('click', function() {
            field.select();
        });
    }

    function createCopyButton(name, embed, content) {
        var button = document.createElement('button');
        button.className = 'copy-link-button' + (embed ? ' copy-embed-button' : '');
        button.type = 'button';
        button.title = embed ? 'Copy embed code' : 'Copy link';
        button.setAttribute('aria-label', button.title + (embed ? ' for ' : ' to ') + name);
        var icon = document.createElement('img');
        icon.src = embed ? '/images/copy-embed.svg' : '/images/copy-link.svg';
        icon.alt = '';
        button.appendChild(icon);
        button.addEventListener('click', async function() {
            ensureDialog();
            var text = content();
            var attempt = ++copyAttempt;
            document.getElementById('copy-link-dialog-title').textContent = button.title;
            dialog.querySelector('label').textContent = embed ? 'HTML embed code' : 'Shareable link';
            dialog.querySelector('.dialog-close').setAttribute('aria-label', 'Close copy dialog');
            field.value = text;
            field.rows = embed ? 4 : 2;
            status.textContent = 'Copying...';
            dialog.showModal();
            field.focus();
            field.select();

            if (!navigator.clipboard || !navigator.clipboard.writeText) {
                status.textContent = 'Select and copy the ' + (embed ? 'embed code' : 'link') + ' manually.';
                return;
            }

            try {
                await navigator.clipboard.writeText(text);
                if (attempt === copyAttempt) {
                    status.textContent = embed ? 'Embed code copied.' : 'Link copied.';
                }
            } catch (error) {
                if (attempt === copyAttempt) {
                    status.textContent = 'Could not copy automatically. Select and copy the '
                        + (embed ? 'embed code' : 'link') + ' manually.';
                }
            }
        });
        return button;
    }

    window.createCopyLinkButton = function(path, name) {
        return createCopyButton(name, false, function() {
            return new URL(path, window.location.origin).href;
        });
    };

    window.createCopyEmbedButton = function(track) {
        return createCopyButton(track.trackName, true, function() {
            var url = new URL('/embed.html', window.location.origin);
            url.searchParams.set('track', track.trackid);
            function escapeAttribute(value) {
                return String(value).replace(/&/g, '&amp;').replace(/"/g, '&quot;')
                    .replace(/</g, '&lt;').replace(/>/g, '&gt;');
            }
            return '<iframe src="' + escapeAttribute(url.href) + '" title="'
                + escapeAttribute(track.trackName + ' by ' + track.artistAlias + ' | Reason Station')
                + '" width="100%" height="193" style="border:0" loading="lazy"></iframe>';
        });
    };
}());
