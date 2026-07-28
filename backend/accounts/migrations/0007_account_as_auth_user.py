import uuid

import accounts.models
from django.db import migrations, models


def import_legacy_auth_users(apps, schema_editor):
    if "auth_user" not in schema_editor.connection.introspection.table_names():
        return

    Account = apps.get_model("accounts", "Account")
    with schema_editor.connection.cursor() as cursor:
        cursor.execute("""
            SELECT username, first_name, last_name, email, password,
                   is_staff, is_active, is_superuser, last_login
              FROM auth_user
        """)
        legacy_users = cursor.fetchall()

    for (legacy_username, first_name, last_name, email, password, is_staff,
         is_active, is_superuser, last_login) in legacy_users:
        username = legacy_username.strip().lower()
        account = Account.objects.filter(username=username).first()
        if account is None:
            display_name = f"{first_name} {last_name}".strip() or legacy_username
            account = Account(
                id=uuid.uuid4(),
                username=username,
                display_name=display_name[:40],
                email=email.lower(),
                email_verified=bool(email),
                password=password,
                is_active=is_active,
            )
        elif is_staff or is_superuser:
            # Administrator credentials were historically stored in auth_user.
            account.password = password
            account.email = email.lower() or account.email
            account.is_active = is_active
        account.is_staff = account.is_staff or is_staff
        account.is_superuser = account.is_superuser or is_superuser
        account.last_login = account.last_login or last_login
        account.save()


def migrate_admin_log_fk(apps, schema_editor):
    if schema_editor.connection.vendor != "postgresql":
        return
    tables = schema_editor.connection.introspection.table_names()
    if "django_admin_log" not in tables or "auth_user" not in tables:
        return

    # django_admin_log was created against auth_user before Account became
    # AUTH_USER_MODEL. Preserve its rows while changing the FK from int to UUID.
    with schema_editor.connection.cursor() as cursor:
        cursor.execute("""
            ALTER TABLE django_admin_log
            ADD COLUMN IF NOT EXISTS account_user_id uuid
        """)
        cursor.execute("""
            UPDATE django_admin_log AS log
               SET account_user_id = account.id
              FROM auth_user AS legacy
              JOIN accounts_account AS account
                ON account.username = LOWER(legacy.username)
             WHERE log.user_id = legacy.id
        """)
        cursor.execute("""
            SELECT tc.constraint_name
              FROM information_schema.table_constraints AS tc
              JOIN information_schema.key_column_usage AS kcu
                ON tc.constraint_name = kcu.constraint_name
               AND tc.constraint_schema = kcu.constraint_schema
             WHERE tc.table_schema = current_schema()
               AND tc.table_name = 'django_admin_log'
               AND tc.constraint_type = 'FOREIGN KEY'
               AND kcu.column_name = 'user_id'
        """)
        for (constraint_name,) in cursor.fetchall():
            cursor.execute(
                f'ALTER TABLE django_admin_log DROP CONSTRAINT "{constraint_name}"')
        cursor.execute("ALTER TABLE django_admin_log DROP COLUMN user_id")
        cursor.execute("""
            ALTER TABLE django_admin_log
            RENAME COLUMN account_user_id TO user_id
        """)
        cursor.execute("""
            ALTER TABLE django_admin_log
            ALTER COLUMN user_id SET NOT NULL
        """)
        cursor.execute("""
            ALTER TABLE django_admin_log
            ADD CONSTRAINT django_admin_log_user_id_account_fk
            FOREIGN KEY (user_id) REFERENCES accounts_account(id)
            DEFERRABLE INITIALLY DEFERRED
        """)
        cursor.execute("""
            CREATE INDEX IF NOT EXISTS django_admin_log_user_id_idx
            ON django_admin_log(user_id)
        """)


class Migration(migrations.Migration):
    dependencies = [
        ("accounts", "0006_accountsession_accounttoken_session"),
        ("auth", "0012_alter_user_first_name_max_length"),
    ]

    operations = [
        migrations.AlterModelManagers(
            name="account",
            managers=[("objects", accounts.models.AccountManager())],
        ),
        migrations.SeparateDatabaseAndState(
            state_operations=[
                migrations.RemoveField(
                    model_name="account",
                    name="password_hash",
                ),
                migrations.AddField(
                    model_name="account",
                    name="password",
                    field=models.CharField(
                        db_column="password_hash",
                        max_length=128,
                    ),
                ),
            ],
            database_operations=[],
        ),
        migrations.AddField(
            model_name="account",
            name="groups",
            field=models.ManyToManyField(
                blank=True,
                help_text="The groups this user belongs to. A user will get all permissions granted to each of their groups.",
                related_name="user_set",
                related_query_name="user",
                to="auth.group",
                verbose_name="groups",
            ),
        ),
        migrations.AddField(
            model_name="account",
            name="is_staff",
            field=models.BooleanField(default=False),
        ),
        migrations.AddField(
            model_name="account",
            name="is_superuser",
            field=models.BooleanField(
                default=False,
                help_text="Designates that this user has all permissions without explicitly assigning them.",
                verbose_name="superuser status",
            ),
        ),
        migrations.AddField(
            model_name="account",
            name="last_login",
            field=models.DateTimeField(blank=True, null=True, verbose_name="last login"),
        ),
        migrations.AddField(
            model_name="account",
            name="user_permissions",
            field=models.ManyToManyField(
                blank=True,
                help_text="Specific permissions for this user.",
                related_name="user_set",
                related_query_name="user",
                to="auth.permission",
                verbose_name="user permissions",
            ),
        ),
        migrations.RunPython(
            import_legacy_auth_users,
            migrations.RunPython.noop,
        ),
        migrations.RunPython(
            migrate_admin_log_fk,
            migrations.RunPython.noop,
        ),
    ]
