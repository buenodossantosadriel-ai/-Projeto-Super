ALTER TABLE public.projeto_super_control_requests
  DROP CONSTRAINT IF EXISTS projeto_super_control_requests_action_check;

ALTER TABLE public.projeto_super_control_requests
  ADD CONSTRAINT projeto_super_control_requests_action_check
  CHECK (
    action = ANY (ARRAY[
      'camera_front',
      'camera_back',
      'screen_share',
      'stop_session',
      'microphone_live',
      'microphone_record',
      'remote_back',
      'remote_home'
    ]::text[])
    OR action ~ '^open_app:[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$'
    OR action ~ '^tap:([0-9]|[1-9][0-9]{1,2}|1000):([0-9]|[1-9][0-9]{1,2}|1000)$'
    OR (
      left(action, 10) = 'type_text:'
      AND length(action) > 10
      AND length(action) <= 2010
    )
  );
