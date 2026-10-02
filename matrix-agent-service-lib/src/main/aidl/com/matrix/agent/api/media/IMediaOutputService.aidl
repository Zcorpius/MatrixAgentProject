package com.matrix.agent.api.media;
import com.matrix.agent.api.media.MediaOutputSnapshot;
import com.matrix.agent.api.media.IMediaOutputCallback;

/** System media route, not an individual player's AudioTrack route. */
interface IMediaOutputService {
    MediaOutputSnapshot getSnapshot();
    MediaOutputSnapshot select(int output);
    MediaOutputSnapshot subscribe(IMediaOutputCallback callback);
    void unsubscribe(IMediaOutputCallback callback);
}
