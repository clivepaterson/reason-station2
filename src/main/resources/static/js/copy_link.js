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
            '<input id="copy-link-field" type="text" readonly>' +
            '<p class="copy-link-status" role="status"></p>';
        document.body.appendChild(dialog);
        field = dialog.querySelector('input');
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

    window.createCopyLinkButton = function(path, name) {
        var button = document.createElement('button');
        button.className = 'copy-link-button';
        button.type = 'button';
        button.title = 'Copy link';
        button.setAttribute('aria-label', 'Copy link to ' + name);
        var icon = document.createElement('img');
        icon.src = '/images/copy-link.svg';
        icon.alt = '';
        button.appendChild(icon);
        button.addEventListener('click', async function() {
            ensureDialog();
            var url = new URL(path, window.location.origin).href;
            var attempt = ++copyAttempt;
            field.value = url;
            status.textContent = 'Copying...';
            dialog.showModal();
            field.focus();
            field.select();

            if (!navigator.clipboard || !navigator.clipboard.writeText) {
                status.textContent = 'Select and copy the link manually.';
                return;
            }

            try {
                await navigator.clipboard.writeText(url);
                if (attempt === copyAttempt) {
                    status.textContent = 'Link copied.';
                }
            } catch (error) {
                if (attempt === copyAttempt) {
                    status.textContent = 'Could not copy automatically. Select and copy the link manually.';
                }
            }
        });
        return button;
    };
}());
