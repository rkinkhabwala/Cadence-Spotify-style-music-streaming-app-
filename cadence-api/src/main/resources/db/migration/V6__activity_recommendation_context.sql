-- Slice 3.1 (D88): context the recommender needs on every track-played event.
-- session_id: the client's listening session; recommendation_id/rec_position: the recommended list and slot the
-- track was played from (attribution). Set by the first report of a playback that carries them.
ALTER TABLE play_events
    ADD COLUMN session_id        varchar(128),
    ADD COLUMN recommendation_id varchar(64),
    ADD COLUMN rec_position      integer CHECK (rec_position >= 0);
