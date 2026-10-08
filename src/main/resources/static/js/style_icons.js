(function() {
    function styleFamily(style) {
        var value = (style || '').toLowerCase();
        if (/metal|rock|punk|grunge|emo/.test(value)) return 'rock';
        if (/electro|house|techno|trance|dubstep|drum|jungle|garage|breakbeat|idm|synth|edm|dance/.test(value)) {
            return 'electronic';
        }
        if (/jazz|blues|funk|soul|r&b|gospel|disco|boogie/.test(value)) return 'jazz';
        if (/classical|baroque|orchestral|opera|choral|score|chamber/.test(value)) return 'classical';
        if (/folk|country|bluegrass|americana|celtic|flamenco|acoustic/.test(value)) return 'folk';
        if (/hip-hop|rap|trap|drill|boom bap/.test(value)) return 'hiphop';
        if (/ambient|drone|ethereal|atmospheric|soundscape|cinematic|darkwave|witch house/.test(value)) return 'ambient';
        if (/reggae|ska|dub|afrobeat|highlife|latin|salsa|samba|bossa|cumbia|reggaeton|tango|klezmer/.test(value)) {
            return 'world';
        }
        if (/pop/.test(value)) return 'pop';
        return 'other';
    }

    function createMusicStyleIcon(style) {
        var normalized = (style || 'Other').trim() || 'Other';
        var family = styleFamily(normalized);
        var image = document.createElement('img');
        image.className = 'track-card-art';
        image.src = '/images/style-icons/' + family + '.svg';
        image.alt = normalized + ' style icon';
        image.width = 96;
        image.height = 96;
        image.decoding = 'async';
        return image;
    }

    window.createMusicStyleIcon = createMusicStyleIcon;
})();
