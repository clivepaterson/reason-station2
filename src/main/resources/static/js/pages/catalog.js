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
                    trackLink(item, window.formatTrackFilename(item)),
                    formatFileSize(item.filesize),
                    formatDate(item.dateUploaded),
                    formatRating(item.averageRating),
                    window.createCatalogDownloadLink(
                        'track', item.trackid, item.trackName, item.filename),
                    $(window.createCopyLinkButton(
                        '/tracks/' + encodeURIComponent(item.trackid), item.trackName))
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
                    item.patchImgUri && item.patchImgUri.trim() ? $('<img>')
                        .addClass('catalog-patch-image')
                        .attr({
                            src: item.imageUrl || '/api/patches/' + encodeURIComponent(item.patchid) + '/image',
                            alt: 'Image for ' + item.patchName,
                            loading: 'lazy'
                        }) : '',
                    item.description || '',
                    patchLink(item, item.filename),
                    item.ext || '',
                    formatDate(item.dateUploaded),
                    window.createCatalogDownloadLink(
                        'patch', item.patchid, item.patchName, item.filename),
                    $(window.createCopyLinkButton(
                        '/patches/' + encodeURIComponent(item.patchid), item.patchName))
                ];
            }
        },
        'archived-songs': archiveConfig('songs'),
        'archived-refills': archiveConfig('refills')
    };

    function archiveConfig(type) {
        var prefix = 'archived-' + type;
        return {
            endpoint: '/api/catalog/' + prefix,
            pageId: prefix + '-page',
            tableId: prefix + '-table',
            statusId: prefix + '-status',
            paginationId: prefix + '-pagination',
            defaultSort: 'dateCreated',
            label: type === 'songs' ? 'archived song' : 'archived refill',
            filterFormId: prefix + '-filter-form',
            filterInputId: prefix + '-filter-input',
            pageSizeId: prefix + '-page-size',
            cells: function(item) {
                var extension = (item.extension || (item.filename || '').split('.').pop())
                    .replace(/^\./, '').toLowerCase();
                var icon = type === 'songs' && (extension === 'rns' || extension === 'rps')
                    ? '/images/archive-' + extension + '.png' : '/images/icon-file.svg';
                if (type === 'refills') {
                    if (extension === 'cmb') {
                        icon = '/images/archive-cmb.png';
                    } else if (extension === 'rex' || extension === 'rx2') {
                        icon = '/images/archive-rex.png';
                    } else if (extension === 'zyp') {
                        icon = '/images/archive-zyp.png';
                    } else if (extension === 'xwv') {
                        icon = '/images/archive-xwv.png';
                    } else if (extension === 'sm4') {
                        icon = '/images/archive-sm4.png';
                    }
                }
                var details = $('<div>').addClass('archive-entry').append(
                    $('<img>').attr({src: icon, alt: ''}),
                    $('<div>').append(
                        $('<strong>').addClass('archive-entry-title')
                            .text(item.name || item.filename || 'Untitled'),
                        $('<span>').text('by ' + (item.creator || 'Unknown creator')),
                        $('<small>').text(item.filename || 'Filename unavailable')
                    )
                );
                var file = $('<div>').addClass('archive-file').append(
                    $('<span>').text(item.extension ? item.extension.toUpperCase() : 'Unknown type'),
                    $('<small>').text(formatFileSize(item.filesize) || 'Size unavailable')
                );
                var cells = [details];
                if (type === 'songs') {
                    cells.push(item.style || 'Not recorded');
                }
                cells.push(file, item.dateCreated
                    ? item.dateCreated.split('-').reverse().join('-') : 'Not recorded');
                if (type === 'songs') {
                    cells.push(formatRating(item.averageRating));
                }
                var label = item.name || item.filename || 'archive file';
                cells.push(item.downloadAvailable
                    ? $('<a>').addClass('catalog-download-link archive-download-button')
                        .attr({
                            href: '/api/catalog/' + prefix + '/download?'
                                + new URLSearchParams({key: item.archiveKey}).toString(),
                            download: item.filename || '',
                            'aria-label': 'Download ' + label
                        })
                        .append($('<img>').attr({src: '/images/download.svg', alt: ''}), 'Download')
                    : $('<button>').addClass('archive-download-button')
                        .attr({type: 'button', title: 'No stored file is available for this archive entry.'})
                        .prop('disabled', true).text('Unavailable'));
                return cells;
            }
        };
    }

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
            pageSize: config.pageSizeId && url.searchParams.get('pageSize') === '100' ? 100 : 25,
            filter: config.filterInputId ? (url.searchParams.get('filter') || '') : ''
        };
    }

    function pageUrl(url, page) {
        var target = new URL(url.href);
        if (page === 1) {
            target.searchParams.delete('page');
        } else {
            target.searchParams.set('page', page);
        }
        return target;
    }

    function updateArchivePagination(pagination, result, url) {
        pagination.find('.archive-skip-back')
            .prop('disabled', result.page <= 1)
            .data('page', Math.max(1, result.page - 10));
        pagination.find('.archive-skip-forward')
            .prop('disabled', result.page >= result.totalPages)
            .data('page', Math.min(result.totalPages, result.page + 10));
        var links = pagination.find('.archive-page-links').empty();
        links.append($('<span>').attr('aria-current', 'page').text(result.page));
        for (var page = result.page + 1; page <= Math.min(result.page + 10, result.totalPages); page++) {
            links.append($('<a>').addClass('archive-page-link')
                .attr({href: pageUrl(url, page).href, 'aria-label': 'Page ' + page})
                .data('page', page).text(page));
        }
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
        var requestId = config.requestId = (config.requestId || 0) + 1;
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
        if (config.pageSizeId) {
            $('#' + config.pageSizeId).val(String(state.pageSize));
        }

        var query = new URLSearchParams({
            page: state.page,
            sort: state.sort,
            direction: state.direction
        });
        if (state.filter) {
            query.set('filter', state.filter);
        }
        if (config.pageSizeId) {
            query.set('pageSize', state.pageSize);
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
                if (config.requestId !== requestId) {
                    return;
                }
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
                if (config.pageSizeId) {
                    updateArchivePagination(pagination, result, url);
                }
            })
            .catch(function(error) {
                if (config.requestId !== requestId) {
                    return;
                }
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
                'click', '.catalog-previous-page, .catalog-next-page, .archive-skip-pages, .archive-page-link', function(event) {
                    if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || event.button !== 0) {
                        return;
                    }
                    event.preventDefault();
                    var page = $(this).data('page');
                    window.showPage(pageUrl(new URL(window.location.href), page), true);
                });
            if (config.pageSizeId) {
                $('#' + config.pageSizeId).on('change', function() {
                    var url = pageUrl(new URL(window.location.href), 1);
                    url.searchParams.set('pageSize', $(this).val());
                    window.showPage(url, true);
                });
            }
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
