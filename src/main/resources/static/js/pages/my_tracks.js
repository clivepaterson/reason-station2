window.initializeMyTracks = function() {
    var allowedExtensions = ['.rps', '.rns', '.reason'];
    var uploadButton = $('#upload-track-button');
    var uploadForm = $('#upload-track-form');
    var uploadDialogBackdrop = $('#upload-dialog-backdrop');
    var tracksStatus = $('#tracks-status');
    var tracksList = $('#tracks-list');
    var uploadSubmitButton = uploadForm.find('button[type="submit"]');
    var trackTextPattern = /^[A-Za-z0-9()\[\]_.'+\-]+$/;
    function closeUploadForm() {
        if (!uploadForm.length) {
            return;
        }
        uploadForm[0].reset();
        uploadSubmitButton.prop('disabled', false);
        $('#upload-status').removeClass('error success').text('');
        uploadForm.attr('hidden', true);
        uploadDialogBackdrop.attr('hidden', true);
    }

    function showTracks(tracks) {
        tracksList.empty();
        if (tracks.length === 0) {
            tracksList.append($('<p>').text('You have not uploaded any tracks yet.'));
            return;
        }

        tracks.forEach(function(track) {
            var item = $(window.createTrackCard(track, {
                deletable: true,
                includeArtistInDownloadLabel: false,
                showArtist: true
            }));
            tracksList.append(item);
        });
    }

    function loadTracks() {
        return fetch('/api/tracks/me')
            .then(function(response) {
                if (!response.ok) {
                    throw new Error('Unable to load your tracks.');
                }
                return response.json();
            })
            .then(showTracks);
    }

    function refreshTracksIfVisible() {
        if ($('#my-tracks-page').is('[hidden]')) {
            return;
        }
        loadTracks().catch(function(error) {
            tracksStatus.text(error.message);
        });
    }

    fetch('/api/me')
        .then(function(response) {
            if (!response.ok) {
                throw new Error('Sign in to view and upload your tracks.');
            }
            return response.json();
        })
        .then(function(user) {
            uploadButton.removeAttr('hidden');
            tracksStatus.text('Signed in as ' + user.username + '.');
            return loadTracks();
        })
        .catch(function(error) {
            tracksStatus.text(error.message);
            tracksList.empty();
            uploadButton.attr('hidden', true);
            closeUploadForm();
        });

    uploadButton.off('click.siteTracks').on('click.siteTracks', function() {
            uploadForm[0].reset();
            uploadSubmitButton.prop('disabled', false);
            $('#upload-status').removeClass('error success').text('');
            uploadForm.removeAttr('hidden');
            uploadDialogBackdrop.removeAttr('hidden');
            $('#artist-alias').trigger('focus');
    });

    tracksList.off('click.siteTracks', '.delete-track-button').on('click.siteTracks', '.delete-track-button', function() {
        var button = $(this);
        var item = button.closest('.track-card');
        var trackName = item.find('h3').text();
        var trackId = button.attr('data-track-id');

        if (!window.confirm('Delete "' + trackName + '"? This cannot be undone.')) {
            return;
        }

        button.prop('disabled', true)
            .attr({
                'aria-label': 'Deleting track',
                title: 'Deleting track'
            });
        fetch('/api/tracks/' + encodeURIComponent(trackId), {
            method: 'DELETE'
        })
            .then(function(response) {
                if (!response.ok) {
                    throw new Error('Unable to delete this track.');
                }
                item.remove();
                if (!tracksList.find('.track-card').length) {
                    tracksList.append($('<p>').text('You have not uploaded any tracks yet.'));
                }
            })
            .catch(function(error) {
                button.prop('disabled', false)
                    .attr({
                        'aria-label': 'Delete track',
                        title: 'Delete track'
                    });
                item.find('.delete-error').remove();
                item.append($('<p>').addClass('delete-error form-message error').text(error.message));
            });
    });

    $(document).off('mousedown.siteTracks').on('mousedown.siteTracks', function(event) {
        if (!$(event.target).closest('#upload-track-form, #upload-track-button').length) {
            closeUploadForm();
        }
    });

    var activeUploads = 0;
    var uploadingList = $('#uploading-list');
    var uploadingItems = $('#uploading-items');

    function hideUploadingListWhenComplete() {
        if (activeUploads === 0) {
            uploadingList.attr('hidden', true);
        }
    }

    function uploadFile(file, artistAlias, trackName, musicStyle) {
        var formData = new FormData();
        formData.append('artistAlias', artistAlias);
        formData.append('trackName', trackName);
        formData.append('musicStyle', musicStyle);
        formData.append('file', file);

        if (activeUploads === 0) {
            uploadingItems.empty();
        }
        activeUploads++;
        uploadingList.removeAttr('hidden');

        var item = $('<article>').addClass('upload-item');
        var status = $('<p>').addClass('upload-item-status').text('Starting...');
        var progress = $('<progress>').attr({
            max: 100,
            value: 0,
            'aria-label': 'Upload progress for ' + file.name
        });
        item.append($('<h3>').text(file.name));
        item.append(progress);
        item.append(status);
        uploadingItems.append(item);

        return new Promise(function(resolve) {
            var request = new XMLHttpRequest();
            request.open('POST', '/api/tracks');
            request.upload.addEventListener('progress', function(event) {
                if (!event.lengthComputable) {
                    status.text('Uploading...');
                    return;
                }
                var percentage = Math.round((event.loaded / event.total) * 100);
                progress.val(percentage);
                status.text('Uploading... ' + percentage + '%');
            });
            request.addEventListener('load', function() {
                activeUploads--;
                if (request.status >= 200 && request.status < 300) {
                    progress.val(100);
                    status.text('Complete');
                    refreshTracksIfVisible();
                    resolve({success: true});
                } else {
                    var errorMessage = 'Unable to upload "' + file.name + '".';
                    try {
                        var error = JSON.parse(request.responseText);
                        errorMessage = error.error || errorMessage;
                    } catch (parseError) {
                        // Use the generic message when the server does not return JSON.
                    }
                    status.text(errorMessage);
                    item.addClass('error');
                    resolve({success: false, message: errorMessage});
                }
                hideUploadingListWhenComplete();
            });
            request.addEventListener('error', function() {
                activeUploads--;
                var errorMessage = 'Unable to upload "' + file.name + '".';
                status.text(errorMessage);
                item.addClass('error');
                resolve({success: false, message: errorMessage});
                hideUploadingListWhenComplete();
            });
            request.addEventListener('abort', function() {
                activeUploads--;
                var errorMessage = 'Upload cancelled for "' + file.name + '".';
                status.text(errorMessage);
                item.addClass('error');
                resolve({success: false, message: errorMessage});
                hideUploadingListWhenComplete();
            });
            request.send(formData);
        });
    }

    uploadForm.off('submit.siteTracks').on('submit.siteTracks', function(event) {
        event.preventDefault();
        var files = Array.prototype.slice.call($('#track-file')[0].files);
        var artistAlias = $('#artist-alias').val();
        var trackName = $('#track-name').val();
        var musicStyle = $('#music-style').val();
        var invalidFile = files.find(function(file) {
            var filename = file.name.toLowerCase();
            return !allowedExtensions.some(function(extension) {
                return filename.endsWith(extension);
            });
        });

        if (!artistAlias || !trackName || !musicStyle || !trackTextPattern.test(artistAlias)
                || !trackTextPattern.test(trackName)) {
            $('#upload-status')
                .removeClass('success')
                .addClass('error')
                .text("Artist alias and track name are required, and may contain only letters, numbers, and ()[]_.'+-; choose a music style.");
            return;
        }

        if (invalidFile) {
            $('#upload-status')
                .removeClass('success')
                .addClass('error')
                .text('Only .rps, .rns, and .reason files are allowed.');
            return;
        }

        $('#upload-status').removeClass('error success').text('Uploading...');
        artistAlias = artistAlias.trim();
        trackName = trackName.trim();
        musicStyle = musicStyle.trim();
        $('#track-file').val('');
        uploadForm.attr('hidden', true);
        uploadDialogBackdrop.attr('hidden', true);
        Promise.all(files.map(function(file) {
            return uploadFile(file, artistAlias, trackName, musicStyle);
        }))
            .then(function(results) {
                var failures = results.filter(function(result) {
                    return !result.success;
                });
                if (failures.length) {
                    $('#upload-status')
                        .removeClass('success')
                        .addClass('error')
                        .text(failures.length + ' upload' + (failures.length === 1 ? '' : 's') + ' failed.');
                } else {
                    $('#upload-status')
                        .removeClass('error')
                        .addClass('success')
                        .text('Track upload complete.');
                }
                if (activeUploads === 0) {
                    return loadTracks();
                }
            })
            .catch(function(error) {
                $('#upload-status')
                    .removeClass('success')
                    .addClass('error')
                    .text(error.message);
            });
    });
};
