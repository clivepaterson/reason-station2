window.loadSearchPage = function(url) {
    var searchTerm = (url.searchParams.get('search') || '').trim();
    var requestedPage = Number.parseInt(url.searchParams.get('page') || '1', 10);
    var page = Number.isInteger(requestedPage) && requestedPage > 0 ? requestedPage : 1;
    var searchResults = $('#search-results');
    var searchPagination = $('#search-pagination');
    $('#search-status').text(searchTerm ? 'Searching for "' + searchTerm + '"...' : 'Enter a search term.');
    if (!searchTerm) {
        searchResults.empty();
        searchPagination.attr('hidden', true);
        return;
    }

    fetch('/api/discovery/search?query=' + encodeURIComponent(searchTerm) + '&page=' + page)
        .then(function(response) {
            return response.json().then(function(body) {
                if (!response.ok) {
                    throw new Error(body.error || 'Unable to search tracks.');
                }
                return body;
            });
        })
        .then(function(searchPage) {
            var tracks = searchPage.results;
            searchResults.empty();
            $('#search-status').text(searchPage.totalElements + ' track'
                + (searchPage.totalElements === 1 ? '' : 's') + ' found.');
            searchPagination.attr('hidden', searchPage.totalPages <= 1);
            $('#search-page-status').text('Page ' + searchPage.page + ' of ' + searchPage.totalPages);
            $('#search-previous-page')
                .prop('disabled', searchPage.page <= 1)
                .data('page', searchPage.page - 1);
            $('#search-next-page')
                .prop('disabled', searchPage.page >= searchPage.totalPages)
                .data('page', searchPage.page + 1);
            if (tracks.length === 0) {
                searchResults.append($('<p>').text('No tracks found.'));
                return;
            }
            tracks.forEach(function(track) {
                searchResults.append(window.createTrackCard(track, {
                    filenameFallback: 'By ' + track.artistAlias,
                    includeArtistInDownloadLabel: true,
                    showArtist: true
                }));
            });
        })
        .catch(function(error) {
            $('#search-status').text(error.message);
            searchResults.empty();
            searchPagination.attr('hidden', true);
        });
};

window.updateSearchButtonState = function() {
    var searchExpression = $('#track-search-input').val() || '';
    var alphanumericCharacters = searchExpression.match(/[\p{L}\p{N}]/gu) || [];
    $('#track-search-form button[type="submit"]').prop('disabled', alphanumericCharacters.length < 2);
};

window.initializeSearchPage = function() {
    $('#track-search-input').on('input', window.updateSearchButtonState);
    $('#search-previous-page, #search-next-page').on('click', function() {
        var url = new URL(window.location.href);
        var page = $(this).data('page');
        if (page === 1) {
            url.searchParams.delete('page');
        } else {
            url.searchParams.set('page', page);
        }
        window.showPage(url, true);
    });
    window.updateSearchButtonState();
};
