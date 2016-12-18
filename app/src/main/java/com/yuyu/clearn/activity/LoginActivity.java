package com.yuyu.clearn.activity;

import android.animation.Animator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.AppCompatCheckBox;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;

import com.afollestad.materialdialogs.MaterialDialog;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.sdsmdg.tastytoast.TastyToast;
import com.yuyu.clearn.R;
import com.yuyu.clearn.api.retrofit.Member;
import com.yuyu.clearn.api.retrofit.RestInterface;
import com.yuyu.clearn.view.Constant;
import com.yuyu.clearn.view.Task;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import rx.Observable;
import rx.Subscriber;

public class LoginActivity extends AppCompatActivity {

    @BindView(R.id.id_edit)
    AutoCompleteTextView id_edit;
    @BindView(R.id.pw_edit)
    EditText pw_edit;
    @BindView(R.id.check_btn)
    AppCompatCheckBox check_btn;
    @BindView(R.id.save_btn)
    AppCompatCheckBox save_btn;
    @BindView(R.id.login_btn)
    Button login_btn;
    @BindView(R.id.find_btn)
    Button find_btn;
    @BindView(R.id.register_btn)
    Button register_btn;
    @BindView(R.id.login_logo)
    ImageView login_logo;

    private final String TAG = LoginActivity.class.getSimpleName();
    private final String REGISTER_URL = "A", FIND_URL = "B", LOGIN_LOGO_IMG = "login_logo.png";
    private final String LOGIN = "LOGIN", TOKEN = "TOKEN", STATUS = "STATUS", ID = "ID", PW = "PW", CHECK = "CHECK", SAVE = "SAVE";
    private final int ANI_DURATION = 500;

    private Task task;
    private Context context;
    private SharedPreferences preferences;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        ButterKnife.bind(this);
        context = this;
        preferences = getSharedPreferences(LOGIN, MODE_PRIVATE);
        RestInterface.init();
        Glide.with(context).load(RestInterface.BASE + RestInterface.RESOURCES + LOGIN_LOGO_IMG)
                .diskCacheStrategy(DiskCacheStrategy.SOURCE)
                .into(login_logo);
        buttonCustomSet(context, Typeface.createFromAsset(getAssets(), Constant.FONT), login_btn, find_btn, register_btn);
        // 아이디 저장, 자동 로그인이 활성화 되어있는지 STATUS로 확인 후 분기에 맞게 실행
        loginDataLoad(preferences.getString(STATUS, null), id_edit, pw_edit);
    }

    @Override
    public void onBackPressed() {
        if (Constant.CURRENT_TIME + Constant.BACK_TIME < System.currentTimeMillis()) {
            Constant.CURRENT_TIME = System.currentTimeMillis();
            TastyToast.makeText(context, getString(R.string.onBackPressed), TastyToast.LENGTH_SHORT, TastyToast.WARNING);
        } else {
            super.onBackPressed();
        }
    }

    // OnClick 메소드 설정
    @OnClick({R.id.login_btn, R.id.register_btn, R.id.find_btn})
    public void onButtonMethod(View view) {
        int vid = view.getId();
        if (vid == R.id.login_btn) {
            loginPrepare(id_edit, pw_edit);
        } else if (vid == R.id.register_btn || vid == R.id.find_btn) {
            if (networkCheck(context)) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RestInterface.BASE + (vid == R.id.register_btn ? REGISTER_URL : FIND_URL))));
            } else {
                TastyToast.makeText(context, getString(R.string.login_internet_err), TastyToast.LENGTH_SHORT, TastyToast.ERROR);
            }
        }
    }

    // 아이디 저장 설정 / 자동 로그인과 중복되지 않음
    @OnClick({R.id.check_btn, R.id.check_txt})
    public void onCheckMethod(View view) {
        if (view.getId() == R.id.check_txt) {
            check_btn.setChecked(!check_btn.isChecked());
        }
        save_btn.setChecked(false);
    }

    // 자동 로그인 설정 / 아이디 저장과 중복되지 않음
    @OnClick({R.id.save_btn, R.id.save_txt})
    public void onSaveMethod(View view) {
        if (view.getId() == R.id.save_txt) {
            save_btn.setChecked(!save_btn.isChecked());
        }
        check_btn.setChecked(false);
    }

    public void loginDataLoad(String data, EditText id_edit, EditText pw_edit) {
        Observable.just(data)
                .filter(s -> s != null)
                .flatMap(s -> Observable.just(s)
                        .groupBy(status -> status.equals(CHECK)))
                .subscribe(group -> {
                    id_edit.setText(preferences.getString(ID, null));
                    pw_edit.setText(group.getKey() ? preferences.getString(PW, null) : null);
                    check_btn.setChecked(group.getKey());
                    save_btn.setChecked(!group.getKey());
                    if (group.getKey()) {
                        loginPrepare(id_edit, pw_edit);
                    }
                });
    }

    public void loginPrepare(EditText id_edit, EditText pw_edit) {
        if (!networkCheck(context)) {
            TastyToast.makeText(context, getString(R.string.login_internet_err), TastyToast.LENGTH_SHORT, TastyToast.ERROR);

        } else {
            String[] loginValue = new String[2];
            Observable.just(id_edit, pw_edit)
                    // EditText 공백 여부 확인
                    .filter(editText -> {
                        boolean empty = TextUtils.isEmpty(editText.getText().toString());
                        if (empty) {
                            editText.setError(getString(R.string.login_required_err));
                            editText.requestFocus();
                        }
                        return !empty;
                    })
                    .map(editText -> editText.getText().toString())
                    .doOnUnsubscribe(() -> {
                        if (loginValue.length == 2) {
                            task = new Task(context);
                            task.onPreExecute();
                            RestInterface.getRestClient()
                                    .create(RestInterface.PostLogin.class)
                                    .login(LOGIN.toLowerCase(), loginValue[0], loginValue[1])
                                    .subscribe(new Subscriber<Member>() {
                                        @Override
                                        public void onCompleted() {
                                        }

                                        @Override
                                        public void onError(Throwable e) {
                                            task.onPostExecute(null);
                                            Log.e(TAG, String.valueOf(e));
                                            TastyToast.makeText(context, getString(R.string.login_internet_err), TastyToast.LENGTH_SHORT, TastyToast.ERROR);
                                        }

                                        @Override
                                        public void onNext(Member member) {
                                            task.onPostExecute(null);
                                            loginProcess(member, loginValue);
                                        }
                                    });
                        }
                    })
                    .subscribe(s -> {
                        if (s.equals(id_edit.getText().toString())) {
                            loginValue[0] = s;
                            preferences.edit().putString(STATUS, check_btn.isChecked() ? CHECK : save_btn.isChecked() ? SAVE : null).apply();
                            preferences.edit().putString(ID, check_btn.isChecked() ? s : save_btn.isChecked() ? s : null).apply();
                        } else {
                            loginValue[1] = s;
                            preferences.edit().putString(PW, check_btn.isChecked() ? s : null).apply();
                        }
                    });
        }
    }

    public void loginProcess(Member member, String[] loginValue) {
        int v_num = member.getV_num();
        String beforeToken = member.getP_token();
        String afterToken = getSharedPreferences(TOKEN, MODE_PRIVATE).getString(TOKEN, loginValue[0]);
        // 로그인에 실패했을 경우
        if (v_num == -1) {
            TastyToast.makeText(context, getString(R.string.login_failed), TastyToast.LENGTH_SHORT, TastyToast.ERROR);

            // 로그인에 성공했으나 가져온 토큰의 값이 저장된 토큰의 값과 다를 경우
            // (처음 로그인을 했을 경우 or 클라이언트의 토큰 값이 변경되었을 경우)
        } else if (!beforeToken.equals(afterToken)) {
            new MaterialDialog.Builder(context)
                    .content(beforeToken.equals(loginValue[0]) ? getString(R.string.login_token_new) : getString(R.string.login_token_change))
                    .positiveText(getString(R.string.login_yes))
                    .negativeText(getString(R.string.login_no))
                    .onPositive((dialog, which) -> {
                        dialog.dismiss();
                        task.onPreExecute();
                        RestInterface.getRestClient()
                                .create(RestInterface.PostToken.class)
                                .token(TOKEN.toLowerCase(), loginValue[0], loginValue[1], afterToken, beforeToken)
                                .subscribe(new Subscriber<Void>() {
                                    @Override
                                    public void onCompleted() {
                                    }

                                    @Override
                                    public void onError(Throwable e) {
                                        task.onPostExecute(null);
                                        Log.e(TAG, String.valueOf(e));
                                        TastyToast.makeText(context, getString(R.string.login_internet_err), TastyToast.LENGTH_SHORT, TastyToast.ERROR);
                                    }

                                    @Override
                                    public void onNext(Void aVoid) {
                                        task.onPostExecute(null);
                                        TastyToast.makeText(context, getString(R.string.login_success), TastyToast.LENGTH_SHORT, TastyToast.SUCCESS);
                                        startActivity(new Intent(context, LoginActivity.class));
                                        finish();
                                    }
                                });
                    })
                    .onNegative((dialog, which) -> dialog.cancel()).show();

            // 로그인에 성공했으나 시청할 영상이 없을 경우
        } else if (v_num == 0) {
            TastyToast.makeText(context, getString(R.string.login_video_err), TastyToast.LENGTH_SHORT, TastyToast.CONFUSING);

            // 로그인에 성공하면 response 받은 v_num과 p_token을 다음 액티비티로 전달하고 실행
        } else {
            Intent intent = new Intent(context, VideoActivity.class);
            intent.putExtra(Constant.V_NUM, v_num);
            intent.putExtra(Constant.P_TOKEN, beforeToken);
            startActivity(intent);
            finish();
        }
    }

    // 버튼 커스텀 설정
    public void buttonCustomSet(Context context, Typeface typeface, Button... btns) {
        Observable.from(btns)
                .subscribe(btn -> {
                    btn.setTypeface(typeface);
                    btn.animate()
                            .translationY(btn.getBottom() + 100 * (context.getResources().getDisplayMetrics().density))
                            .setInterpolator(new AccelerateInterpolator())
                            .setDuration(0)
                            .setListener(new Animator.AnimatorListener() {

                                @Override
                                public void onAnimationStart(Animator animator) {
                                }

                                @Override
                                public void onAnimationEnd(Animator animator) {
                                    btn.setVisibility(View.VISIBLE);
                                    btn.animate()
                                            .translationY(0 - 5 * (context.getResources().getDisplayMetrics().density))
                                            .setInterpolator(new DecelerateInterpolator())
                                            .setDuration(ANI_DURATION)
                                            .start();
                                }

                                @Override
                                public void onAnimationCancel(Animator animator) {
                                }

                                @Override
                                public void onAnimationRepeat(Animator animator) {
                                }
                            });
                });
    }

    // 네트워크 연결 여부 확인
    public boolean networkCheck(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
        return networkInfo != null;
    }

}