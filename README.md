# Projeto Super

Este repositório mantém o dashboard web e o aplicativo Android nativo original em branches separadas para que a publicação web não misture o processo de compilação do APK.

## Dashboard web
- Branch: `main`
- Arquivo publicado: `dashboard.html`
- `index.html` redireciona para o dashboard.
- O dashboard utiliza o endpoint Supabase `projeto-super-controle`.

## Aplicativo Android nativo original
- Branch: `android-native`
- Módulo: `app/` (Kotlin/Jetpack Compose).
- Configuração Gradle, Manifest, serviços Android, Edge Function, migrações e workflow de compilação ficam nessa branch.
- Workflow: `.github/workflows/android.yml`.
- O APK de teste é publicado como artefato do GitHub Actions; não é uma versão de produção.

## Atenção
A branch `main` é a origem do dashboard web e não deve ser usada para compilar o APK. Para compilar e validar o aplicativo Android, selecione `android-native`. Não misture arquivos das duas branches sem revisar a compatibilidade entre dashboard, Edge Function e aplicativo.