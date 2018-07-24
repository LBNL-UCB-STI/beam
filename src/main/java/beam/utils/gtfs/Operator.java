package beam.utils.gtfs;

import com.google.gson.annotations.SerializedName;

import javax.annotation.Generated;

@Generated("net.hexar.json2pojo")
@SuppressWarnings("unused")
public class Operator {

    @SerializedName("ContactTelephoneNumber")
    private String mContactTelephoneNumber;
    @SerializedName("DefaultLanguage")
    private String mDefaultLanguage;
    @SerializedName("Id")
    private String mId;
    @SerializedName("Montiored")
    private Boolean mMontiored;
    @SerializedName("Name")
    private String mName;
    @SerializedName("OtherModes")
    private String mOtherModes;
    @SerializedName("PrimaryMode")
    private String mPrimaryMode;
    @SerializedName("PrivateCode")
    private String mPrivateCode;
    @SerializedName("ShortName")
    private String mShortName;
    @SerializedName("SiriOperatorRef")
    private String mSiriOperatorRef;
    @SerializedName("TimeZone")
    private String mTimeZone;
    @SerializedName("WebSite")
    private String mWebSite;

    public String getContactTelephoneNumber() {
        return mContactTelephoneNumber;
    }

    public void setContactTelephoneNumber(String contactTelephoneNumber) {
        mContactTelephoneNumber = contactTelephoneNumber;
    }

    public String getDefaultLanguage() {
        return mDefaultLanguage;
    }

    public void setDefaultLanguage(String defaultLanguage) {
        mDefaultLanguage = defaultLanguage;
    }

    public String getId() {
        return mId;
    }

    public void setId(String id) {
        mId = id;
    }

    public Boolean getMontiored() {
        return mMontiored;
    }

    public void setMontiored(Boolean montiored) {
        mMontiored = montiored;
    }

    public String getName() {
        return mName;
    }

    public void setName(String name) {
        mName = name;
    }

    public String getOtherModes() {
        return mOtherModes;
    }

    public void setOtherModes(String otherModes) {
        mOtherModes = otherModes;
    }

    public String getPrimaryMode() {
        return mPrimaryMode;
    }

    public void setPrimaryMode(String primaryMode) {
        mPrimaryMode = primaryMode;
    }

    public String getPrivateCode() {
        return mPrivateCode;
    }

    public void setPrivateCode(String privateCode) {
        mPrivateCode = privateCode;
    }

    public String getShortName() {
        return mShortName;
    }

    public void setShortName(String shortName) {
        mShortName = shortName;
    }

    public String getSiriOperatorRef() {
        return mSiriOperatorRef;
    }

    public void setSiriOperatorRef(String siriOperatorRef) {
        mSiriOperatorRef = siriOperatorRef;
    }

    public String getTimeZone() {
        return mTimeZone;
    }

    public void setTimeZone(String timeZone) {
        mTimeZone = timeZone;
    }

    public String getWebSite() {
        return mWebSite;
    }

    public void setWebSite(String webSite) {
        mWebSite = webSite;
    }

    @Override
    public String toString() {
        return "Operator{" +
                "mContactTelephoneNumber='" + mContactTelephoneNumber + '\'' +
                ", mDefaultLanguage='" + mDefaultLanguage + '\'' +
                ", mId='" + mId + '\'' +
                ", mMontiored=" + mMontiored +
                ", mName='" + mName + '\'' +
                ", mOtherModes='" + mOtherModes + '\'' +
                ", mPrimaryMode='" + mPrimaryMode + '\'' +
                ", mPrivateCode='" + mPrivateCode + '\'' +
                ", mShortName='" + mShortName + '\'' +
                ", mSiriOperatorRef='" + mSiriOperatorRef + '\'' +
                ", mTimeZone='" + mTimeZone + '\'' +
                ", mWebSite='" + mWebSite + '\'' +
                '}';
    }
}
