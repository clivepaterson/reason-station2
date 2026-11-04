(function() {
    var catalogs = {
        artists: {
            endpoint: '/api/catalog/artists',
            pageId: 'artists-page',
            tableId: 'artists-table',
            statusId: 'artists-status',
            paginationId: 'artists-pagination',
            defaultSort: 'dateCreated',
            label: 'artist',
            filterFormId: 'artists-filter-form',
            filterInputId: 'artists-filter-input',
            cells: function(item) {
                return [
                    $('<a>').attr('href', '/index.html?artistAlias='
                        + encodeURIComponent(item.artistAlias)).text(item.artistAlias),
                    formatDate(item.dateCreated),
                    item.trackCount.toLocaleString(),
                    item.patchCount.toLocaleString()
                ];
            }
        },
        tracks: {
            endpoint: '/api/catalog/tracks',
            pageId: 'tracks-catalog-page',
            tableId: 'tracks-catalog-table',
            statusId: 'tracks-catalog-status',
            paginationId: 'tracks-catalog-pagination',
            defaultSort: 'dateUploaded',
            label: 'track',
            filterFormId: 'tracks-catalog-filter-form',
            filterInputId: 'tracks-catalog-filter-input',
            cells: function(item) {
                return [
                    artistLink(item.artistAlias),
                    trackLink(item, item.trackName),
                    item.musicStyle,
                    trackLink(item, item.filename),
                    formatFileSize(item.filesize),
                    formatDate(item.dateUploaded),
                    formatRating(item.averageRating),
                    window.createCatalogDownloadLink(
                        'track', item.trackid, item.trackName, item.filename)
                ];
            }
        },
        patches: {
            endpoint: '/api/catalog/patches',
            pageId: 'patches-page',
            tableId: 'patches-table',
            statusId: 'patches-status',
            paginationId: 'patches-pagination',
            defaultSort: 'dateUploaded',
            label: 'patch',
            filterFormId: 'patches-filter-form',
            filterInputId: 'patches-filter-input',
            cells: function(item) {
                return [
                    artistLink(item.artistAlias),
                    patchLink(item, item.patchName),
                    item.description || '',
                    patchLink(item, item.filename),
                    item.ext || '',
                    formatDate(item.dateUploaded),
                    window.createCatalogDownloadLink(
                        'patch', item.patchid, item.patchName, item.filename)
                ];
            }
        }
    };

    function artistLink(alias) {
        return $('<a>')
            .attr('href', '/index.html?artistAlias=' + encodeURIComponent(alias))
            .text(alias);
    }

    function trackLink(track, label) {
        return $('<a>')
            .addClass('track-detail-link')
            .attr('href', '/tracks/' + encodeURIComponent(track.trackid))
            .text(label);
    }

    function patchLink(patch, label) {
        return $('<a>')
            .addClass('patch-detail-link')
            .attr('href', '/patches/' + encodeURIComponent(patch.patchid))
            .text(label);
    }

    window.createCatalogDownloadLink = function(type, id, name, filename) {
        var resource = type === 'patch' ? 'patches' : 'tracks';
        var icon = $('<img>')
            .attr({
                src: '/images/download.svg',
                alt: ''
            });
        return $('<a>')
            .addClass('catalog-download-link')
            .attr({
                href: '/api/' + resource + '/' + encodeURIComponent(id) + '/download',
                'aria-label': 'Download ' + type + ' ' + name,
                title: 'Download ' + type,
                download: filename || ''
            })
            .append(icon);
    };

    function formatDate(value) {
        return value ? new Intl.DateTimeFormat(undefined, {
            year: 'numeric',
            month: 'short',
            day: 'numeric'
        }).format(new Date(value)) : '';
    }

    function formatFileSize(bytes) {
        if (bytes === null || bytes === undefined) {
            return '';
        }
        if (bytes < 1024) {
            return bytes + ' B';
        }
        var units = ['KB', 'MB', 'GB', 'TB'];
        var value = bytes / 1024;
        var unitIndex = 0;
        while (value >= 1024 && unitIndex < units.length - 1) {
            value /= 1024;
            unitIndex += 1;
        }
        return value.toFixed(value >= 10 ? 0 : 1) + ' ' + units[unitIndex];
    }

    function formatRating(rating) {
        return rating === null || rating === undefined ? 'Not rated' : rating.toFixed(1);
    }

    function appendCell(row, value) {
        var cell = $('<td>');
        if (value && value.jquery) {
            cell.append(value);
        } else {
            cell.text(value);
        }
        row.append(cell);
    }

    function currentState(config, url) {
        var page = Number.parseInt(url.searchParams.get('page') || '1', 10);
        var sort = url.searchParams.get('sort') || config.defaultSort;
        var direction = url.searchParams.get('direction')
            || (sort === config.defaultSort ? 'desc' : 'asc');
        return {
            page: Number.isInteger(page) && page > 0 ? page : 1,
            sort: sort,
            direction: direction === 'desc' ? 'desc' : 'asc',
            filter: config.filterInputId ? (url.searchParams.get('filter') || '') : ''
        };
    }

    function updateSortHeaders(config, state) {
        $('#' + config.tableId + ' th').removeAttr('aria-sort');
        $('#' + config.tableId + ' [data-sort]').each(function() {
            var button = $(this);
            button.find('.sort-indicator').remove();
            if (button.data('sort') === state.sort) {
                button.closest('th').attr('aria-sort',
                    state.direction === 'asc' ? 'ascending' : 'descending');
                button.append($('<span>').addClass('sort-indicator')
                    .attr('aria-hidden', 'true')
                    .text(state.direction === 'asc' ? ' \u25b2' : ' \u25bc'));
            }
        });
    }

    window.loadCatalogPage = function(catalogName, url) {
        var config = catalogs[catalogName];
        var state = currentState(config, url);
        var tableBody = $('#' + config.tableId + ' tbody');
        var pagination = $('#' + config.paginationId);
        $('#' + config.statusId).text('Loading ' + config.label + 's...');
        tableBody.empty();
        pagination.attr('hidden', true);
        updateSortHeaders(config, state);
        if (config.filterInputId) {
            $('#' + config.filterInputId).val(state.filter);
        }

        var query = new URLSearchParams({
            page: state.page,
            sort: state.sort,
            direction: state.direction
        });
        if (state.filter) {
            query.set('filter', state.filter);
        }
        fetch(config.endpoint + '?' + query.toString())
            .then(function(response) {
                return response.json().then(function(body) {
                    if (!response.ok) {
                        throw new Error(body.detail || body.error
                            || 'Unable to load ' + config.label + 's.');
                    }
                    return body;
                });
            })
            .then(function(result) {
                $('#' + config.statusId).text(result.totalElements.toLocaleString() + ' '
                    + config.label + (result.totalElements === 1 ? '' : 's') + '.');
                result.items.forEach(function(item) {
                    var row = $('<tr>');
                    config.cells(item).forEach(function(value) {
                        appendCell(row, value);
                    });
                    tableBody.append(row);
                });
                if (result.items.length === 0) {
                    tableBody.append($('<tr>').append(
                        $('<td>').attr('colspan',
                            $('#' + config.tableId + ' thead th').length)
                            .addClass('catalog-empty')
                            .text('No ' + config.label + 's found.')));
                }

                pagination.attr('hidden', result.totalPages <= 1);
                pagination.find('.catalog-page-status')
                    .text('Page ' + result.page + ' of ' + result.totalPages);
                pagination.find('.catalog-previous-page')
                    .prop('disabled', result.page <= 1)
                    .data('page', result.page - 1);
                pagination.find('.catalog-next-page')
                    .prop('disabled', result.page >= result.totalPages)
                    .data('page', result.page + 1);
            })
            .catch(function(error) {
                $('#' + config.statusId).text(error.message);
                pagination.attr('hidden', true);
            });
    };

    window.initializeCatalogPages = function() {
        Object.keys(catalogs).forEach(function(catalogName) {
            var config = catalogs[catalogName];
            $('#' + config.tableId).on('click', '[data-sort]', function() {
                var url = new URL(window.location.href);
                var selectedSort = $(this).data('sort');
                var current = currentState(config, url);
                var nextDirection = current.sort === selectedSort && current.direction === 'asc'
                    ? 'desc' : 'asc';
                url.searchParams.set('sort', selectedSort);
                url.searchParams.set('direction', nextDirection);
                url.searchParams.delete('page');
                window.showPage(url, true);
            });
            $('#' + config.paginationId).on(
                'click', '.catalog-previous-page, .catalog-next-page', function() {
                    var url = new URL(window.location.href);
                    var page = $(this).data('page');
                    if (page === 1) {
                        url.searchParams.delete('page');
                    } else {
                        url.searchParams.set('page', page);
                    }
                    window.showPage(url, true);
                });
            if (config.filterFormId) {
                $('#' + config.filterInputId)
                    .on('beforeinput', function(event) {
                        var inputEvent = event.originalEvent;
                        if (inputEvent.data
                                && !/^[A-Za-z0-9.]+$/.test(inputEvent.data)) {
                            event.preventDefault();
                        }
                    })
                    .on('input', function() {
                        this.value = this.value
                            .replace(/[^A-Za-z0-9.]/g, '')
                            .slice(0, 100);
                    });
                $('#' + config.filterFormId).on('submit', function(event) {
                    event.preventDefault();
                    var url = new URL(window.location.href);
                    var filter = $('#' + config.filterInputId).val().trim();
                    if (filter) {
                        url.searchParams.set('filter', filter);
                    } else {
                        url.searchParams.delete('filter');
                    }
                    url.searchParams.delete('page');
                    window.showPage(url, true);
                });
            }
        });
    };
}());
