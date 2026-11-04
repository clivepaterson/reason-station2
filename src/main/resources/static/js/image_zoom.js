(function() {
    var dialog;
    var expandedImage;

    function getDialog() {
        if (dialog) {
            return dialog;
        }
        dialog = document.getElementById('tutorial-image-dialog');
        if (!dialog) {
            dialog = document.createElement('dialog');
            dialog.id = 'tutorial-image-dialog';
            dialog.className = 'tutorial-image-dialog';
            dialog.setAttribute('aria-label', 'Expanded image');
            var content = document.createElement('div');
            content.className = 'tutorial-image-dialog-content';
            var close = document.createElement('button');
            close.id = 'tutorial-image-dialog-close';
            close.className = 'tutorial-image-dialog-close';
            close.type = 'button';
            close.setAttribute('aria-label', 'Close expanded image');
            close.textContent = '\u00d7';
            content.appendChild(close);
            expandedImage = document.createElement('img');
            expandedImage.id = 'tutorial-image-dialog-image';
            expandedImage.className = 'tutorial-image-dialog-image';
            expandedImage.alt = '';
            content.appendChild(expandedImage);
            dialog.appendChild(content);
            document.body.appendChild(dialog);
        }
        expandedImage = document.getElementById('tutorial-image-dialog-image');
        document.getElementById('tutorial-image-dialog-close').addEventListener('click', function() {
            dialog.close();
        });
        dialog.addEventListener('mousedown', function(event) {
            if (event.target === dialog) {
                dialog.close();
            }
        });
        return dialog;
    }

    function openImage(image) {
        var imageDialog = getDialog();
        if (typeof imageDialog.showModal !== 'function') {
            return;
        }
        expandedImage.src = image.currentSrc || image.src;
        expandedImage.alt = image.alt;
        imageDialog.showModal();
    }

    window.enableImageZoom = function(image) {
        image.setAttribute('data-image-zoom', '');
        image.setAttribute('role', 'button');
        image.setAttribute('tabindex', '0');
        image.setAttribute('aria-label', 'View larger image' + (image.alt ? ': ' + image.alt : ''));
    };

    document.addEventListener('click', function(event) {
        if (event.target.matches('img[data-image-zoom]')) {
            openImage(event.target);
        }
    });
    document.addEventListener('keydown', function(event) {
        if (event.target.matches('img[data-image-zoom]') && (event.key === 'Enter' || event.key === ' ')) {
            event.preventDefault();
            openImage(event.target);
        }
    });
    document.querySelectorAll('.patch-detail-image').forEach(window.enableImageZoom);
}());
