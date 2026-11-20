ALTER TABLE public.projeto_super_control_requests
DROP CONSTRAINT projeto_super_control_requests_action_check;
ALTER TABLE public.projeto_super_control_requests
ADD CONSTRAINT projeto_super_control_requests_action_check CHECK (
  action = ANY (ARRAY[
    'camera_front'::text, 'camera_back'::text, 'screen_share'::text,
    'stop_session'::text, 'microphone_live'::text, 'microphone_record'::text,
    'remote_back'::text, 'remote_home'::text
  ])
  OR action ~ '^open_app:[A-Za-z0-9_]+([.][A-Za-z0-9_]+)+$'
  OR action ~ '^tap:([0-9]|[1-9][0-9]{1,2}|1000):([0-9]|[1-9][0-9]{1,2}|1000)$'
  OR (left(action, 10) = 'type_text:' AND length(action) > 10 AND length(action) <= 2010)
  OR action ~ '^swipe:([0-9]|[1-9][0-9]{1,2}|1000):([0-9]|[1-9][0-9]{1,2}|1000):([0-9]|[1-9][0-9]{1,2}|1000):([0-9]|[1-9][0-9]{1,2}|1000):[0-9]{1,4}$'
);