window.initializeTutorialsPage = function() {
    var tutorialImageSelector = '.tutorial-article-header img, .tutorial-screenshot img';
    var tutorialImageDialog = document.getElementById('tutorial-image-dialog');
    var tutorialImageDialogImage = document.getElementById('tutorial-image-dialog-image');

    $(tutorialImageSelector).each(function() {
        var description = this.alt ? ': ' + this.alt : '';
        $(this)
            .attr('role', 'button')
            .attr('tabindex', '0')
            .attr('aria-label', 'View larger image' + description);
    });

    function openTutorialImage(image) {
        if (!tutorialImageDialog || typeof tutorialImageDialog.showModal !== 'function') {
            return;
        }
        tutorialImageDialogImage.src = image.currentSrc || image.src;
        tutorialImageDialogImage.alt = image.alt;
        tutorialImageDialog.showModal();
    }

    $(document).on('click', tutorialImageSelector, function() {
        openTutorialImage(this);
    });

    $(document).on('keydown', tutorialImageSelector, function(event) {
        if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            openTutorialImage(this);
        }
    });

    $('#tutorial-image-dialog-close').on('click', function() {
        tutorialImageDialog.close();
    });

    $('#tutorial-image-dialog').on('mousedown', function(event) {
        if (event.target === this) {
            this.close();
        }
    });
};
