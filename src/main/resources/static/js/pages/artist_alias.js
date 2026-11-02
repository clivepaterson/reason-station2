window.initializeArtistPage = function() {
    var params = new URLSearchParams(window.location.search);
    var artistAlias = params.get('artistAlias');
    var heading = document.getElementById('artist-alias-heading');
    var tracksList = document.getElementById('artist-tracks');
    var tracksLoading = document.getElementById('artist-tracks-loading');
    var patchesList = document.getElementById('artist-patches');
    var patchesLoading = document.getElementById('artist-patches-loading');
    var patchesMessage = document.getElementById('artist-patches-message');
    var patchesTable = document.getElementById('artist-patches-table');
    var patchesTableBody = patchesTable.querySelector('tbody');

    if (!artistAlias) {
        heading.textContent = 'Artist not found';
        tracksList.replaceChildren();
        tracksList.hidden = false;
        tracksLoading.hidden = true;
        patchesTableBody.replaceChildren();
        patchesList.hidden = true;
        patchesLoading.hidden = true;
        return;
    }

    heading.textContent = artistAlias;
    document.title = 'Reason Station - ' + artistAlias;
    tracksList.replaceChildren();
    tracksList.hidden = true;
    tracksList.setAttribute('aria-busy', 'true');
    tracksLoading.hidden = false;
    fetch('/api/discovery/artist-tracks?artistAlias=' + encodeURIComponent(artistAlias))
        .then(function(response) {
            if (!response.ok) {
                throw new Error('Unable to load artist tracks.');
            }
            return response.json();
        })
        .then(function(tracks) {
            tracksList.replaceChildren();
            if (tracks.length === 0) {
                tracksList.appendChild(document.createElement('p')).textContent = 'No tracks found.';
            } else {
                tracks.forEach(function(track) {
                    tracksList.appendChild(window.createTrackCard(track));
                });
            }
            tracksList.hidden = false;
            tracksList.setAttribute('aria-busy', 'false');
            tracksLoading.hidden = true;
        })
        .catch(function() {
            tracksList.replaceChildren();
            tracksList.appendChild(document.createElement('p')).textContent = 'Unable to load artist tracks.';
            tracksList.hidden = false;
            tracksList.setAttribute('aria-busy', 'false');
            tracksLoading.hidden = true;
        });

    patchesList.hidden = true;
    patchesList.setAttribute('aria-busy', 'true');
    patchesLoading.hidden = false;
    fetch('/api/discovery/artist-patches?artistAlias=' + encodeURIComponent(artistAlias))
        .then(function(response) {
            if (!response.ok) {
                throw new Error('Unable to load artist patches.');
            }
            return response.json();
        })
        .then(function(patches) {
            patchesTableBody.replaceChildren();
            patchesMessage.hidden = true;
            patchesTable.hidden = false;
            if (patches.length === 0) {
                var emptyCell = document.createElement('td');
                emptyCell.className = 'catalog-empty';
                emptyCell.colSpan = 6;
                emptyCell.textContent = 'No patches found.';
                var emptyRow = document.createElement('tr');
                emptyRow.appendChild(emptyCell);
                patchesTableBody.appendChild(emptyRow);
            } else {
                patches.forEach(function(patch) {
                    var values = [
                        patch.patchName,
                        patch.description || '',
                        patch.filename,
                        patch.ext || '',
                        patch.dateUploaded
                            ? new Intl.DateTimeFormat(undefined, {
                                year: 'numeric',
                                month: 'short',
                                day: 'numeric'
                            }).format(new Date(patch.dateUploaded))
                            : ''
                    ];
                    var row = document.createElement('tr');
                    values.forEach(function(value) {
                        var cell = document.createElement('td');
                        cell.textContent = value;
                        row.appendChild(cell);
                    });
                    var downloadCell = document.createElement('td');
                    downloadCell.appendChild(window.createCatalogDownloadLink(
                        'patch', patch.patchid, patch.patchName, patch.filename)[0]);
                    row.appendChild(downloadCell);
                    patchesTableBody.appendChild(row);
                });
            }
            patchesList.hidden = false;
            patchesList.setAttribute('aria-busy', 'false');
            patchesLoading.hidden = true;
        })
        .catch(function() {
            patchesTableBody.replaceChildren();
            patchesTable.hidden = true;
            patchesMessage.textContent = 'Unable to load artist patches.';
            patchesMessage.hidden = false;
            patchesList.hidden = false;
            patchesList.setAttribute('aria-busy', 'false');
            patchesLoading.hidden = true;
        });
};
