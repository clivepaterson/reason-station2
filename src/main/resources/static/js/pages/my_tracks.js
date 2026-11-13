window.initializeMyTracks = function() {
    var allowedExtensions = ['.rps', '.rns', '.reason'];
    var allowedPatchExtensions = [
        '.sxt', '.xwv', '.smp', '.drex', '.drum', '.rv7', '.sm4', '.echo',
        '.pulver', '.gator', '.cmb', '.thor', '.kong', '.drp', '.zyp', '.patch',
        '.repatch', '.sf2', '.rx2', '.rcy', '.rex'
    ];
    var uploadButton = $('#upload-track-button');
    var uploadForm = $('#upload-track-form');
    var uploadPatchButton = $('#upload-patch-button');
    var uploadPatchForm = $('#upload-patch-form');
    var uploadDialogBackdrop = $('#upload-dialog-backdrop');
    var tracksStatus = $('#tracks-status');
    var tracksList = $('#tracks-list');
    var patchesStatus = $('#my-patches-status');
    var patchesList = $('#my-patches-list');
    var uploadSubmitButton = uploadForm.find('button[type="submit"]');
    var verificationNotice = $('#verification-notice');
    var resendVerificationLink = $('#resend-verification-email');
    var verificationEmailStatus = $('#verification-email-status');
    var trackTextPattern = /^[A-Za-z0-9()\[\]_.'+\- ]+$/;
    var artistAliasAndPatchNamePattern = /^[A-Za-z0-9()\[\]_.'+\- ]+$/;
    var descriptionInvalidCharacters = /[^A-Za-z0-9()\[\]_.'+\- \r\n]/g;
    var editingTrack = null;
    var editingPatch = null;
    var savingEdit = false;
    var dialogTrigger = null;

    function closeUploadForm() {
        if (!uploadForm.length || savingEdit) {
            return;
        }
        editingTrack = null;
        editingPatch = null;
        uploadForm[0].reset();
        uploadSubmitButton.prop('disabled', false);
        $('#upload-status').removeClass('error success').text('');
        uploadForm.attr('hidden', true);
        if (uploadPatchForm.length) {
            uploadPatchForm[0].reset();
        }
        uploadPatchForm.find('button[type="submit"]').prop('disabled', false);
        $('#patch-upload-status').removeClass('error success').text('');
        $('#patch-description-count').text('0 / 200');
        uploadPatchForm.attr('hidden', true);
        uploadDialogBackdrop.attr('hidden', true);
        $('#track-form-title').text('Upload a track');
        $('#patch-form-title').text('Upload a patch');
        uploadSubmitButton.text('Upload');
        uploadPatchForm.find('button[type="submit"]').text('Upload');
        $('#track-file').prop('required', true).prop('multiple', true);
        $('#patch-file').prop('required', true);
        $('.edit-file-help, .edit-file-optional, #track-current-files, #patch-current-files').attr('hidden', true);
        if (dialogTrigger) {
            dialogTrigger.focus();
            dialogTrigger = null;
        }
    }

    function addEditButton(item, kind, data) {
        var button = $('<button>').attr({
            type: 'button',
            'aria-label': 'Edit ' + kind + ' ' + (data.trackName || data.patchName)
        }).addClass('rate-track-button edit-' + kind + '-button').text('Edit');
        button.data('record', data);
        item.find('.track-card-actions').prepend(button);
    }

    function saveEdit(form, url, status, refresh) {
        var formData = new FormData(form[0]);
        ['file', 'image', 'audio'].forEach(function(name) {
            var input = form.find('input[name="' + name + '"]')[0];
            if (!input || !input.files.length) {
                formData.delete(name);
            }
        });
        if (formData.has('description')) {
            formData.set('description', formData.get('description').replace(/\r\n|\r|\n/g, ' '));
        }
        if (['file', 'image', 'audio'].some(function(name) { return formData.has(name); })) {
            var isTrack = form.is(uploadForm);
            var name = formData.get(isTrack ? 'trackName' : 'patchName');
            closeUploadForm();
            uploadInBackground(formData, 'PUT', url, 'Update ' + (isTrack ? 'track: ' : 'patch: ') + name,
                isTrack ? refreshTracksIfVisible : refreshPatchesIfVisible);
            return;
        }
        savingEdit = true;
        form.find('input, textarea, button').prop('disabled', true);
        form.attr('aria-busy', 'true');
        status.removeClass('error success').text('Saving changes...');
        fetch(url, {method: 'PUT', body: formData})
            .then(function(response) {
                if (response.ok) {
                    return;
                }
                var contentType = response.headers.get('Content-Type') || '';
                if (contentType.indexOf('application/json') !== -1) {
                    return response.json().then(function(body) {
                        throw new Error(body.error || 'Unable to save changes.');
                    });
                }
                throw new Error('Unable to save changes.');
            })
            .then(function() {
                savingEdit = false;
                closeUploadForm();
                return refresh();
            })
            .catch(function(error) {
                if (form.is('[hidden]')) {
                    (form.is(uploadForm) ? tracksStatus : patchesStatus)
                        .text('Changes saved, but the list could not be refreshed: ' + error.message);
                } else {
                    status.removeClass('success').addClass('error').text(error.message);
                }
            })
            .finally(function() {
                savingEdit = false;
                form.find('input, textarea, button').prop('disabled', false);
                form.removeAttr('aria-busy');
            });
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
            addEditButton(item, 'track', track);
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

    function loadPatches() {
        patchesStatus.text('Loading your patches...');
        return fetch('/api/patches/me')
            .then(function(response) {
                if (!response.ok) {
                    throw new Error('Unable to load your patches.');
                }
                return response.json();
            })
            .then(function(patches) {
                patchesList.empty();
                patchesStatus.text('');
                if (patches.length === 0) {
                    patchesList.append($('<p>').text('You have not uploaded any patches yet.'));
                    return;
                }
                patches.forEach(function(patch) {
                    var item = $(window.createPatchCard(patch));
                    addEditButton(item, 'patch', patch);
                    patchesList.append(item);
                });
            });
    }

    function showPatchesError(error) {
        patchesStatus.text(error.message);
    }

    function refreshPatchesIfVisible() {
        if (!$('#my-tracks-page').is('[hidden]')) {
            loadPatches().catch(showPatchesError);
        }
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
            tracksStatus.text('Signed in as ' + user.username + '.');
            if (user.emailVerified) {
                uploadButton.removeAttr('hidden');
                uploadPatchButton.removeAttr('hidden');
                verificationNotice.attr('hidden', true);
            } else {
                uploadButton.attr('hidden', true);
                uploadPatchButton.attr('hidden', true);
                verificationNotice.removeAttr('hidden');
            }
            return Promise.all([
                loadTracks().catch(function(error) {
                    tracksStatus.text(error.message);
                }),
                loadPatches().catch(showPatchesError)
            ]);
        })
        .catch(function(error) {
            tracksStatus.text(error.message);
            tracksList.empty();
            patchesList.empty();
            patchesStatus.text('');
            uploadButton.attr('hidden', true);
            uploadPatchButton.attr('hidden', true);
            closeUploadForm();
        });

    resendVerificationLink.off('click.siteTracks').on('click.siteTracks', function(event) {
        event.preventDefault();
        if (resendVerificationLink.attr('aria-disabled') === 'true') {
            return;
        }
        resendVerificationLink.attr('aria-disabled', 'true');
        verificationEmailStatus.removeClass('error success').text(' Sending...');
        fetch('/api/resend-verification-email', {method: 'POST'})
            .then(function(response) {
                return response.json().then(function(body) {
                    if (!response.ok) {
                        throw new Error(body.error || 'Unable to send the verification email.');
                    }
                    return body;
                });
            })
            .then(function(body) {
                verificationEmailStatus.removeClass('error').addClass('success')
                    .text(' ' + body.message);
            })
            .catch(function(error) {
                verificationEmailStatus.removeClass('success').addClass('error')
                    .text(' ' + error.message);
            })
            .finally(function() {
                resendVerificationLink.removeAttr('aria-disabled');
            });
    });

    uploadButton.off('click.siteTracks').on('click.siteTracks', function() {
            if (savingEdit) {
                return;
            }
            closeUploadForm();
            dialogTrigger = this;
            uploadForm[0].reset();
            uploadSubmitButton.prop('disabled', false);
            $('#upload-status').removeClass('error success').text('');
            uploadForm.removeAttr('hidden');
            uploadDialogBackdrop.removeAttr('hidden');
            $('#artist-alias').trigger('focus');
    });

    uploadPatchButton.off('click.siteTracks').on('click.siteTracks', function() {
        if (savingEdit) {
            return;
        }
        closeUploadForm();
        dialogTrigger = this;
        uploadPatchForm[0].reset();
        uploadPatchForm.find('button[type="submit"]').prop('disabled', false);
        $('#patch-upload-status').removeClass('error success').text('');
        uploadPatchForm.removeAttr('hidden');
        uploadDialogBackdrop.removeAttr('hidden');
        $('#patch-artist-alias').trigger('focus');
    });

    tracksList.off('click.siteTracks', '.edit-track-button').on('click.siteTracks', '.edit-track-button', function() {
        if (savingEdit) {
            return;
        }
        closeUploadForm();
        dialogTrigger = this;
        editingTrack = $(this).data('record');
        $('#artist-alias').val(editingTrack.artistAlias);
        $('#track-name').val(editingTrack.trackName);
        $('#music-style').val(editingTrack.musicStyle);
        $('#audio-link').val(editingTrack.audioLink || '');
        $('#track-file').prop('required', false).prop('multiple', false);
        $('#track-form-title').text('Edit track');
        uploadSubmitButton.text('Save changes');
        $('#track-current-files').text('Current Reason file: ' + editingTrack.filename
            + '. Image: ' + (editingTrack.imageUri ? 'uploaded' : 'none')
            + '. MP3: ' + (editingTrack.audioUri ? 'uploaded' : 'none') + '.').removeAttr('hidden');
        uploadForm.find('.edit-file-help, .edit-file-optional').removeAttr('hidden');
        uploadForm.removeAttr('hidden');
        uploadDialogBackdrop.removeAttr('hidden');
        $('#artist-alias').trigger('focus');
    });

    patchesList.off('click.siteTracks', '.edit-patch-button').on('click.siteTracks', '.edit-patch-button', function() {
        if (savingEdit) {
            return;
        }
        closeUploadForm();
        dialogTrigger = this;
        editingPatch = $(this).data('record');
        $('#patch-artist-alias').val(editingPatch.artistAlias);
        $('#patch-name').val(editingPatch.patchName);
        $('#patch-description').val(editingPatch.description || '').trigger('input');
        $('#patch-file').prop('required', false);
        $('#patch-form-title').text('Edit patch');
        uploadPatchForm.find('button[type="submit"]').text('Save changes');
        $('#patch-current-files').text('Current patch file: ' + editingPatch.filename
            + '. Image: ' + (editingPatch.patchImgUri ? 'uploaded' : 'none') + '.').removeAttr('hidden');
        uploadPatchForm.find('.edit-file-help, .edit-file-optional').removeAttr('hidden');
        uploadPatchForm.removeAttr('hidden');
        uploadDialogBackdrop.removeAttr('hidden');
        $('#patch-artist-alias').trigger('focus');
    });

    $('.cancel-upload-dialog').off('click.siteTracks').on('click.siteTracks', closeUploadForm);

    $('#patch-description').off('input.siteTracks').on('input.siteTracks', function() {
        var input = this;
        var value = input.value;
        var selectionStart = input.selectionStart;
        var sanitizedValue = value.replace(descriptionInvalidCharacters, '');

        if (sanitizedValue !== value) {
            var sanitizedSelectionStart = value.substring(0, selectionStart)
                .replace(descriptionInvalidCharacters, '').length;
            input.value = sanitizedValue;
            input.setSelectionRange(sanitizedSelectionStart, sanitizedSelectionStart);
        }
        $('#patch-description-count').text(input.value.length + ' / 200');
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
        if (!$(event.target).closest(
                '#upload-track-form, #upload-track-button, #upload-patch-form, #upload-patch-button').length) {
            closeUploadForm();
        }
    });
    $(document).off('keydown.siteTracks').on('keydown.siteTracks', function(event) {
        var dialog = !uploadForm.is('[hidden]') ? uploadForm : uploadPatchForm;
        if (dialog.is('[hidden]')) {
            return;
        }
        if (event.key === 'Escape') {
            event.preventDefault();
            closeUploadForm();
        } else if (event.key === 'Tab') {
            var fields = dialog.find('input, textarea, button').filter(':visible:enabled');
            var first = fields[0];
            var last = fields[fields.length - 1];
            if (event.shiftKey && document.activeElement === first) {
                event.preventDefault();
                last.focus();
            } else if (!event.shiftKey && document.activeElement === last) {
                event.preventDefault();
                first.focus();
            }
        }
    });

    var activeUploads = 0;
    var uploadingList = $('#uploading-list');
    var uploadingItems = $('#uploading-items');

    function hideUploadingListWhenComplete() {
        if (activeUploads === 0 && !uploadingItems.find('.error').length) {
            uploadingList.attr('hidden', true);
        }
    }

    function uploadFile(file, artistAlias, trackName, musicStyle, audioLink, image, audio) {
        var formData = new FormData();
        formData.append('artistAlias', artistAlias);
        formData.append('trackName', trackName);
        formData.append('musicStyle', musicStyle);
        formData.append('audioLink', audioLink);
        formData.append('file', file);
        if (image) {
            formData.append('image', image);
        }
        if (audio) {
            formData.append('audio', audio);
        }

        return uploadInBackground(formData, 'POST', '/api/tracks', file.name, refreshTracksIfVisible);
    }

    function uploadInBackground(formData, method, url, label, refresh) {
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
            'aria-label': 'Upload progress for ' + label
        });
        var cancelButton = $('<button>').addClass('upload-cancel-button').attr({
            type: 'button',
            'aria-label': 'Cancel upload for ' + label,
            title: 'Cancel upload'
        }).html('&times;');
        item.append($('<h3>').text(label));
        [
            ['file', url.indexOf('/api/patches') === 0 ? 'Patch file: ' : 'Reason file: '],
            ['image', 'Image: '],
            ['audio', 'MP3: ']
        ].forEach(function(field) {
            var file = formData.get(field[0]);
            if (file) {
                item.append($('<p>').addClass('upload-item-file').text(field[1] + file.name));
            }
        });
        item.append(cancelButton);
        item.append(progress);
        item.append(status);
        uploadingItems.append(item);

        function showFailure(message) {
            status.text(message);
            item.addClass('error');
            cancelButton.prop('disabled', false).attr({
                'aria-label': 'Dismiss upload message for ' + label,
                title: 'Dismiss upload message'
            }).off('click').on('click', function() {
                item.remove();
                hideUploadingListWhenComplete();
            });
        }

        return new Promise(function(resolve) {
            var request = new XMLHttpRequest();
            request.open(method, url);
            cancelButton.on('click', function() {
                cancelButton.prop('disabled', true);
                status.text('Cancelling...');
                request.abort();
            });
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
                    cancelButton.remove();
                    progress.val(100);
                    status.text('Complete');
                    refresh();
                    resolve({success: true});
                } else {
                    var errorMessage = 'Unable to upload "' + label + '".';
                    try {
                        var error = JSON.parse(request.responseText);
                        errorMessage = error.error || errorMessage;
                    } catch (parseError) {
                        // Use the generic message when the server does not return JSON.
                    }
                    showFailure(errorMessage);
                    resolve({success: false, message: errorMessage});
                }
                hideUploadingListWhenComplete();
            });
            request.addEventListener('error', function() {
                activeUploads--;
                var errorMessage = 'Unable to upload "' + label + '".';
                showFailure(errorMessage);
                resolve({success: false, message: errorMessage});
                hideUploadingListWhenComplete();
            });
            request.addEventListener('abort', function() {
                activeUploads--;
                var errorMessage = 'Upload cancelled for "' + label + '".';
                showFailure(errorMessage);
                resolve({success: false, message: errorMessage});
                hideUploadingListWhenComplete();
            });
            request.send(formData);
        });
    }

    function uploadPatch(file, artistAlias, patchName, description, image) {
        var formData = new FormData();
        formData.append('artistAlias', artistAlias);
        formData.append('patchName', patchName);
        formData.append('description', description);
        formData.append('file', file);
        if (image) {
            formData.append('image', image);
        }

        return uploadInBackground(formData, 'POST', '/api/patches', file.name, refreshPatchesIfVisible);
    }

    uploadPatchForm.off('submit.siteTracks').on('submit.siteTracks', function(event) {
        event.preventDefault();
        var file = $('#patch-file')[0].files[0];
        var image = $('#patch-image')[0].files[0];
        var artistAlias = $('#patch-artist-alias').val().trim();
        var patchName = $('#patch-name').val().trim();
        var description = $('#patch-description').val();
        var patchUploadStatus = $('#patch-upload-status');
        var submitButton = uploadPatchForm.find('button[type="submit"]');
        var filename = file ? file.name.toLowerCase() : '';
        var hasAllowedExtension = allowedPatchExtensions.some(function(extension) {
            return filename.endsWith(extension);
        });

        if (!artistAlias || !patchName || !artistAliasAndPatchNamePattern.test(artistAlias)
                || !artistAliasAndPatchNamePattern.test(patchName)) {
            patchUploadStatus.removeClass('success').addClass('error')
                .text("Artist alias and patch name are required, and may contain only letters, numbers, spaces, and ()[]_.'+-.");
            return;
        }
        if ((!file && !editingPatch) || (file && (!hasAllowedExtension
                || file.size === 0 || file.size > 30 * 1024 * 1024))) {
            patchUploadStatus.removeClass('success').addClass('error')
                .text('Choose a supported Reason patch file.');
            return;
        }

        if (image && (!/\.(jpe?g|png|bmp|gif)$/i.test(image.name)
                || image.size === 0 || image.size > 30 * 1024 * 1024)) {
            patchUploadStatus.removeClass('success').addClass('error')
                .text('Choose a .jpg, .jpeg, .png, .bmp, or .gif image no larger than 30 MB.');
            return;
        }

        if (savingEdit) {
            return;
        }
        if (editingPatch) {
            saveEdit(uploadPatchForm, '/api/patches/' + encodeURIComponent(editingPatch.patchid),
                patchUploadStatus, loadPatches);
            return;
        }

        submitButton.prop('disabled', true);
        patchUploadStatus.removeClass('error success').text('Uploading...');
        uploadPatchForm.attr('hidden', true);
        uploadDialogBackdrop.attr('hidden', true);

        uploadPatch(file, artistAlias, patchName, description.replace(/\r\n|\r|\n/g, ' '), image)
            .then(function(result) {
                if (!result.success) {
                    throw new Error(result.message);
                }
                uploadPatchForm[0].reset();
                $('#patch-description-count').text('0 / 200');
                patchUploadStatus.removeClass('error').addClass('success')
                    .text('Patch upload complete.');
            })
            .catch(function(error) {
                patchUploadStatus.removeClass('success').addClass('error').text(error.message);
            })
            .finally(function() {
                submitButton.prop('disabled', false);
            });
    });

    uploadForm.off('submit.siteTracks').on('submit.siteTracks', function(event) {
        event.preventDefault();
        var files = Array.prototype.slice.call($('#track-file')[0].files);
        var artistAlias = $('#artist-alias').val().trim();
        var trackName = $('#track-name').val().trim();
        var musicStyle = $('#music-style').val();
        var audioLink = $('#audio-link').val().trim();
        var image = $('#track-image')[0].files[0];
        var audio = $('#track-audio')[0].files[0];
        var invalidFile = files.find(function(file) {
            var filename = file.name.toLowerCase();
            return file.size === 0 || file.size > 80 * 1024 * 1024 || !allowedExtensions.some(function(extension) {
                return filename.endsWith(extension);
            });
        });

        if (!artistAlias || !trackName || !musicStyle || !artistAliasAndPatchNamePattern.test(artistAlias)
                || !trackTextPattern.test(trackName)) {
            $('#upload-status')
                .removeClass('success')
                .addClass('error')
                .text("Artist alias and track name are required, and may contain only letters, numbers, spaces, and ()[]_.'+-; choose a music style.");
            return;
        }

        if (audioLink && !/^https:\/\/((on\.)?soundcloud|audiomack)\.com\/[^\s\\]*$/.test(audioLink)) {
            $('#upload-status')
                .removeClass('success')
                .addClass('error')
                .text('Audio link must start with https://soundcloud.com/, https://on.soundcloud.com/, or https://audiomack.com/ and contain no whitespace or backslashes.');
            return;
        }

        if ((!files.length && !editingTrack) || invalidFile) {
            $('#upload-status')
                .removeClass('success')
                .addClass('error')
                .text('Choose a non-empty .rps, .rns, or .reason file no larger than 80 MB.');
            return;
        }

        if (image && (!/\.(jpe?g|png|bmp|gif)$/i.test(image.name)
                || image.size === 0 || image.size > 30 * 1024 * 1024)) {
            $('#upload-status').removeClass('success').addClass('error')
                .text('Choose a non-empty .jpg, .jpeg, .png, .bmp, or .gif image no larger than 30 MB.');
            return;
        }
        if (audio && (!/\.mp3$/i.test(audio.name)
                || audio.size === 0 || audio.size > 80 * 1024 * 1024)) {
            $('#upload-status').removeClass('success').addClass('error')
                .text('Choose a non-empty .mp3 audio file no larger than 80 MB.');
            return;
        }

        if (savingEdit) {
            return;
        }
        if (editingTrack) {
            saveEdit(uploadForm, '/api/tracks/' + encodeURIComponent(editingTrack.trackid),
                $('#upload-status'), loadTracks);
            return;
        }

        $('#upload-status').removeClass('error success').text('Uploading...');
        artistAlias = artistAlias.trim();
        trackName = trackName.trim();
        musicStyle = musicStyle.trim();
        $('#track-file').val('');
        $('#track-image').val('');
        $('#track-audio').val('');
        uploadForm.attr('hidden', true);
        uploadDialogBackdrop.attr('hidden', true);
        Promise.all(files.map(function(file) {
            return uploadFile(file, artistAlias, trackName, musicStyle, audioLink, image, audio);
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
