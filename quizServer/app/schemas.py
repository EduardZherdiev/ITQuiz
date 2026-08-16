from pydantic import BaseModel, ConfigDict, Field
from typing import List, Optional


class TopicDto(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    code: str
    icon_url: Optional[str] = None
    created_at: int
    updated_at: int
    plays_count: int
    likes_count: int
    views_count: int
    author_user_id: Optional[str] = None
    is_public: bool
    is_active: bool


class TopicTextDto(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    topic_id: int
    language_code: str
    title: str
    description: Optional[str] = None
    abbr: str


class QuestionDto(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    topic_id: int
    difficulty: str
    is_active: bool


class QuestionTextDto(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    question_id: int
    language_code: str
    question_text: str
    explanation: Optional[str] = None


class OptionDto(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    question_id: int
    is_correct: bool


class OptionTextDto(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    option_id: int
    language_code: str
    option_text: str


class AssetDto(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    asset_type: str
    asset_code: Optional[str] = None
    price: int
    is_active: bool


class UserDto(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    google_uid: Optional[str] = None
    email: Optional[str] = None
    display_name: Optional[str] = None
    photo_url: Optional[str] = None
    currency_balance: int
    last_login_at: Optional[int] = None


class UserAssetDto(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    user_id: str
    asset_id: int
    selected: bool
    purchased_at: Optional[int] = None


class CurrencyTransactionDto(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    user_id: str
    amount: int
    reason: str
    created_at: int


class QuizSessionDto(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    user_id: str
    topic_id: int
    mode: str
    difficulty: str
    total_questions: int
    correct_answers: int
    stake: int = 0
    reward_amount: int = 0
    started_at: int
    finished_at: Optional[int] = None


class BootstrapResponse(BaseModel):
    server_revision: int
    topics: List[TopicDto]
    topic_texts: List[TopicTextDto]
    questions: List[QuestionDto]
    question_texts: List[QuestionTextDto]
    options: List[OptionDto]
    option_texts: List[OptionTextDto]
    assets: List[AssetDto]
    user_assets: List[UserAssetDto]
    users: List[UserDto]
    currency_transactions: List[CurrencyTransactionDto]
    quiz_sessions: List[QuizSessionDto]


class AnonymousAuthRequest(BaseModel):
    device_id: str = Field(min_length=8, max_length=128)


class AuthResponse(BaseModel):
    user_id: str
    access_token: str
    expires_at: int


class StartQuizRequest(BaseModel):
    topic_id: int
    mode: str = "solo"
    difficulty: str = "common"
    stake: int = Field(gt=0)
    total_questions: int = Field(gt=0)
    client_session_id: Optional[str] = Field(default=None, min_length=8, max_length=128)


class FinishQuizRequest(BaseModel):
    correct_answers: int = Field(ge=0)
    total_questions: int = Field(gt=0)


class OfflineQuizSyncRequest(BaseModel):
    client_session_id: str = Field(min_length=8, max_length=128)
    topic_id: int
    mode: str = "solo"
    difficulty: str = "common"
    stake: int = Field(gt=0)
    total_questions: int = Field(gt=0)
    correct_answers: int = Field(ge=0)
    cancelled: bool = False


class PurchaseAssetRequest(BaseModel):
    asset_id: int


class TopUpCurrencyRequest(BaseModel):
    amount: int = Field(gt=0, le=1_000_000)
    source: str = "test"
    operation_id: Optional[str] = Field(default=None, min_length=8, max_length=128)


class GooglePlayPurchaseRequest(BaseModel):
    product_id: str = Field(min_length=1, max_length=128)
    purchase_token: str = Field(min_length=1, max_length=512)


class QuizActionResponse(BaseModel):
    session_id: Optional[int] = None
    asset_id: Optional[int] = None
    balance: int
    stake: int = 0
    reward_amount: int = 0
    finished_at: Optional[int] = None
